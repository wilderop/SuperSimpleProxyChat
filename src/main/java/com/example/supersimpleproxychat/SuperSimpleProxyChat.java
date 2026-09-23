package com.example.supersimpleproxychat;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "supersimpleproxychat",
        name = "Super Simple Proxy Chat",
        version = "1.3.2",
        description = "Cross-server and cross-proxy chat with ignore + nick support",
        authors = {"Benjamin"},
        dependencies = {
                @Dependency(id = "papiproxybridge", optional = true)
        }
)
public class SuperSimpleProxyChat {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private String chatFormat = "<{player}> {message}";
    private String redisUri = "";
    private String proxyId = "home";
    private RedisChat redis;

    private boolean papiAvailable = false;
    private Object papiApi;

    // Ignore system (from backends)
    private final Map<UUID, Set<UUID>> globalIgnores = new HashMap<>();

    // Nick system (from backends)
    private final Map<UUID, String> customNicks = new HashMap<>(); // UUID -> MiniMessage string

    private static final ChannelIdentifier CHANNEL_IGNORE = new LegacyChannelIdentifier("backchat:ignore");
    private static final ChannelIdentifier CHANNEL_NICK = new LegacyChannelIdentifier("backchat:nick");
    private static final MinecraftChannelIdentifier CHANNEL_FJR_SKIP =
            MinecraftChannelIdentifier.from("firstjoinreward:skip");
    private static final Path FJR_LAST_SEEN = Path.of("/mnt/pool/skygate/empty-reward/last-seen");
    private static final long FJR_GRACE_MS = 10_000L;
    private final Map<UUID, Long> survivalSideSeen = new ConcurrentHashMap<>();

    @Inject
    public SuperSimpleProxyChat(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

        if (server.getPluginManager().getPlugin("papiproxybridge").isPresent()) {
            try {
                Class<?> papiClass = Class.forName("net.william278.papiproxybridge.api.PlaceholderAPI");
                papiApi = papiClass.getMethod("createInstance").invoke(null);
                papiAvailable = true;
                logger.info("PAPIProxyBridge detected → PlaceholderAPI support enabled!");
            } catch (Exception e) {
                papiApi = null;
                papiAvailable = false;
                logger.warn("PAPIProxyBridge present but API init failed: {}", e.getMessage());
            }
        } else {
            logger.info("PAPIProxyBridge not found → PlaceholderAPI placeholders will not be parsed.");
        }

        // Register plugin channels from backends
        server.getChannelRegistrar().register(CHANNEL_IGNORE, CHANNEL_NICK, CHANNEL_FJR_SKIP);
        startRedis();

        logger.info("Super Simple Proxy Chat enabled with nick + ignore + redis support (redis={})", redis != null);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (redis != null) {
            redis.stop();
            redis = null;
        }
    }

    private void loadConfig() {
        Path configPath = dataDirectory.resolve("config.toml");

        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(dataDirectory);
                String defaultConfig = "chat-format = <{player}> {message}\n";
                Files.writeString(configPath, defaultConfig);
                logger.info("Created default config.toml");
            } catch (IOException e) {
                logger.error("Failed to create default config.toml", e);
            }
        }

        try (var input = Files.newInputStream(configPath)) {
            Properties props = new Properties();
            props.load(input);
            String loaded = props.getProperty("chat-format", chatFormat).trim();

            if (loaded.startsWith("\"") && loaded.endsWith("\"")) {
                loaded = loaded.substring(1, loaded.length() - 1).trim();
            }

            if (!loaded.isEmpty()) {
                chatFormat = loaded;
            }
            redisUri = props.getProperty("redis-uri", "").trim();
            if (redisUri.startsWith("\"") && redisUri.endsWith("\"") && redisUri.length() >= 2) {
                redisUri = redisUri.substring(1, redisUri.length() - 1).trim();
            }
            proxyId = props.getProperty("proxy-id", "home").trim();
            if (proxyId.startsWith("\"") && proxyId.endsWith("\"") && proxyId.length() >= 2) {
                proxyId = proxyId.substring(1, proxyId.length() - 1).trim();
            }
            if (proxyId.isEmpty()) {
                proxyId = "home";
            }
            logger.info("Loaded chat format: {}", chatFormat);
        } catch (IOException e) {
            logger.warn("Failed to load config.toml - using default format", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void formatWithPapi(String format, UUID uuid, String username, Player sender, String message) {
        try {
            java.util.concurrent.CompletableFuture<String> future =
                    (java.util.concurrent.CompletableFuture<String>) papiApi.getClass()
                            .getMethod("formatPlaceholders", String.class, UUID.class)
                            .invoke(papiApi, format, uuid);
            future.whenComplete((formatted, throwable) -> {
                if (throwable != null) {
                    logger.warn("Failed to parse placeholders for {}: {}", username, throwable.getMessage());
                    broadcastToOtherServers(sender, format, message);
                    return;
                }
                broadcastToOtherServers(sender, formatted != null ? formatted : format, message);
            });
        } catch (Exception e) {
            logger.warn("PAPI format failed for {}: {}", username, e.getMessage());
            broadcastToOtherServers(sender, format, message);
        }
    }

    private void startRedis() {
        if (redisUri.isBlank()) {
            logger.info("No redis-uri set; chat is local to this proxy.");
            return;
        }
        try {
            redis = new RedisChat(logger, redisUri, proxyId);
            redis.start(event -> server.getScheduler().buildTask(this, () -> onRedisEvent(event)).schedule());
            redis.loadState(globalIgnores, customNicks);
        } catch (Exception e) {
            redis = null;
            logger.error("Failed to start Redis chat sync; chat is local to this proxy", e);
        }
    }

    private static boolean isolatedServer(String name) {
        return name != null && "horror".equalsIgnoreCase(name);
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        Player sender = event.getPlayer();
        UUID uuid = sender.getUniqueId();
        String username = sender.getUsername();
        String message = event.getMessage();
        String senderServer = sender.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("");
        if (isolatedServer(senderServer)) {
            return;
        }
        boolean onFabric = "fabric".equalsIgnoreCase(senderServer);

        // Keep {player}/{message} intact so nicks (MiniMessage) can be spliced in.
        // PAPI is Paper-only; Fabric has no expansion host and times out.
        if (papiAvailable && papiApi != null && !onFabric) {
            formatWithPapi(chatFormat, uuid, username, sender, message);
        } else {
            broadcastToOtherServers(sender, chatFormat, message);
        }
    }

    private void broadcastToOtherServers(Player sender, String format, String message) {
        String senderServerName = sender.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(null);

        UUID senderUuid = sender.getUniqueId();
        String nick = customNicks.getOrDefault(senderUuid, sender.getUsername());
        Component finalComponent = renderChat(format, nick, message);
        String finalText = MiniMessage.miniMessage().serialize(finalComponent);

        if (senderServerName == null) {
            logger.warn("Sender {} has no current server - not duplicating local chat", sender.getUsername());
            if (redis != null) {
                redis.publishChat(senderUuid, sender.getUsername(), "unknown", finalText);
            }
            return;
        }

        for (Player online : server.getAllPlayers()) {
            String onlineServerName = online.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse(null);

            if (onlineServerName == null || onlineServerName.equals(senderServerName)) continue;
            if (isolatedServer(onlineServerName) || isolatedServer(senderServerName)) continue;

            Set<UUID> ignored = globalIgnores.getOrDefault(online.getUniqueId(), Collections.emptySet());
            if (!ignored.contains(senderUuid)) {
                online.sendMessage(finalComponent);
            }
        }

        if (redis != null) {
            redis.publishChat(senderUuid, sender.getUsername(), senderServerName, finalText);
        }
    }

    /**
     * Splices a MiniMessage nick into chat-format without wrapping it in extra
     * {@code <>} tags ({@code <{player}>} would become {@code <<gradient...>Name>}).
     */
    private static Component renderChat(String format, String nickMini, String message) {
        Component nick = parseMiniLoose(nickMini == null || nickMini.isBlank() ? "unknown" : nickMini);
        Component msg = Component.text(message == null ? "" : message);
        Component out = Component.empty();
        String s = format == null ? "<{player}> {message}" : format;
        while (!s.isEmpty()) {
            int iPlayer = s.indexOf("{player}");
            int iMsg = s.indexOf("{message}");
            int i = -1;
            int len = 0;
            Component insert = null;
            if (iPlayer >= 0 && (iMsg < 0 || iPlayer < iMsg)) {
                i = iPlayer;
                len = 8;
                insert = nick;
            } else if (iMsg >= 0) {
                i = iMsg;
                len = 9;
                insert = msg;
            } else {
                out = out.append(parseMiniLoose(s));
                break;
            }
            if (i > 0) {
                out = out.append(parseMiniLoose(s.substring(0, i)));
            }
            out = out.append(insert);
            s = s.substring(i + len);
        }
        return out;
    }

    private static Component parseMiniLoose(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        int lt = 0;
        int gt = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') lt++;
            else if (c == '>') gt++;
        }
        if (lt != gt) {
            return Component.text(s);
        }
        try {
            return MiniMessage.miniMessage().deserialize(s);
        } catch (Exception e) {
            return Component.text(s);
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        server.getScheduler().buildTask(this, () -> {
            announceJoinLeave(player.getUniqueId(), player.getUsername(), true);
            if (redis == null) {
                return;
            }
            String current = publicServerName(player.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse("network"));
            redis.publishJoin(player.getUniqueId(), player.getUsername(), current);
        }).delay(400, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        String backend = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("");
        if (isSurvivalSide(backend)) {
            markSurvivalSideSeen(player.getUniqueId());
        }
        announceJoinLeave(player.getUniqueId(), player.getUsername(), false);
        if (redis == null) {
            return;
        }
        String current = publicServerName(backend.isBlank() ? "network" : backend);
        redis.publishLeave(player.getUniqueId(), player.getUsername(), current);
    }

    /** Real proxy join/leave only. Vanilla backend join/leave is silenced. */
    private void announceJoinLeave(UUID uuid, String username, boolean join) {
        String nickMini = customNicks.getOrDefault(uuid, username);
        if (nickMini == null || nickMini.isBlank()) {
            nickMini = username;
        }
        String verb = join ? "joined the game" : "left the game";
        Component component = MiniMessage.miniMessage().deserialize(
                "<yellow>" + nickMini + " <yellow>" + verb);
        for (Player online : server.getAllPlayers()) {
            String serverName = online.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse("");
            if (isolatedServer(serverName)) {
                continue;
            }
            online.sendMessage(component);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String dest = event.getServer().getServerInfo().getName();
        boolean destSurvival = isSurvivalSide(dest);
        boolean fromSurvival = event.getPreviousServer()
                .map(prev -> isSurvivalSide(prev.getServerInfo().getName()))
                .orElse(false);
        UUID uuid = event.getPlayer().getUniqueId();
        if (fromSurvival) {
            markSurvivalSideSeen(uuid);
        }
        if (!destSurvival) {
            return;
        }
        boolean recent = wasSurvivalSideRecently(uuid);
        if (!fromSurvival && !recent) {
            return;
        }
        if (!"survival".equalsIgnoreCase(dest)) {
            return;
        }
        byte[] payload = uuid.toString().getBytes(StandardCharsets.UTF_8);
        event.getPlayer().getCurrentServer().ifPresent(conn -> conn.sendPluginMessage(CHANNEL_FJR_SKIP, payload));
    }

    private static boolean isSurvivalSide(String name) {
        return name != null && ("survival".equalsIgnoreCase(name) || "fabric".equalsIgnoreCase(name));
    }

    private void markSurvivalSideSeen(UUID uuid) {
        if (uuid == null) {
            return;
        }
        survivalSideSeen.put(uuid, System.currentTimeMillis());
        try {
            if (!Files.isDirectory(Path.of("/mnt/pool/skygate"))) {
                return;
            }
            Files.createDirectories(FJR_LAST_SEEN);
            Files.writeString(
                    FJR_LAST_SEEN.resolve(uuid.toString()),
                    Long.toString(System.currentTimeMillis()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception ignored) {
        }
    }

    private boolean wasSurvivalSideRecently(UUID uuid) {
        Long mem = survivalSideSeen.get(uuid);
        long now = System.currentTimeMillis();
        if (mem != null && now - mem < FJR_GRACE_MS) {
            return true;
        }
        try {
            Path file = FJR_LAST_SEEN.resolve(uuid.toString());
            if (!Files.isRegularFile(file)) {
                return false;
            }
            long then = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
            return now - then < FJR_GRACE_MS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void onRedisEvent(RedisChat.Event event) {
        if (event == null || event.type == null) {
            return;
        }
        switch (event.type) {
            case "chat" -> {
                if (event.text == null || event.text.isBlank()) {
                    return;
                }
                UUID senderUuid = parseUuid(event.uuid);
                if (senderUuid != null && server.getPlayer(senderUuid).isPresent()) {
                    // Originating proxy already showed in-world chat and cross-server copies.
                    return;
                }
                Component component = MiniMessage.miniMessage().deserialize(event.text);
                String originServer = event.server;
                for (Player online : server.getAllPlayers()) {
                    if (senderUuid != null && senderUuid.equals(online.getUniqueId())) {
                        continue;
                    }
                    String onlineServer = online.getCurrentServer()
                            .map(conn -> conn.getServerInfo().getName())
                            .orElse(null);
                    if (isolatedServer(originServer) || isolatedServer(onlineServer)) {
                        continue;
                    }
                    // Same backend world already received the Paper broadcast.
                    if (originServer != null && originServer.equals(onlineServer)) {
                        continue;
                    }
                    if (senderUuid != null) {
                        Set<UUID> ignored = globalIgnores.getOrDefault(online.getUniqueId(), Collections.emptySet());
                        if (ignored.contains(senderUuid)) {
                            continue;
                        }
                    }
                    online.sendMessage(component);
                }
            }
            case "join" -> {
                if (event.name == null) {
                    return;
                }
                UUID uuid = parseUuid(event.uuid);
                String nick = uuid != null ? customNicks.getOrDefault(uuid, event.name) : event.name;
                Component component = MiniMessage.miniMessage().deserialize(
                        "<yellow>" + nick + " <yellow>joined the game");
                for (Player online : server.getAllPlayers()) {
                    String serverName = online.getCurrentServer()
                            .map(conn -> conn.getServerInfo().getName())
                            .orElse("");
                    if (isolatedServer(serverName)) {
                        continue;
                    }
                    online.sendMessage(component);
                }
            }
            case "leave" -> {
                if (event.name == null) {
                    return;
                }
                UUID uuid = parseUuid(event.uuid);
                String nick = uuid != null ? customNicks.getOrDefault(uuid, event.name) : event.name;
                Component component = MiniMessage.miniMessage().deserialize(
                        "<yellow>" + nick + " <yellow>left the game");
                for (Player online : server.getAllPlayers()) {
                    String serverName = online.getCurrentServer()
                            .map(conn -> conn.getServerInfo().getName())
                            .orElse("");
                    if (isolatedServer(serverName)) {
                        continue;
                    }
                    online.sendMessage(component);
                }
            }
            case "ignore" -> {
                UUID ignorer = parseUuid(event.uuid);
                UUID ignored = parseUuid(event.extraUuid);
                if (ignorer == null || ignored == null || event.action == null) {
                    return;
                }
                Set<UUID> set = globalIgnores.computeIfAbsent(ignorer, k -> new HashSet<>());
                if ("add".equals(event.action)) {
                    set.add(ignored);
                } else if ("remove".equals(event.action)) {
                    set.remove(ignored);
                }
                if (redis != null) {
                    redis.persistIgnore(ignorer, ignored, "add".equals(event.action));
                }
            }
            case "nick" -> {
                UUID uuid = parseUuid(event.uuid);
                if (uuid == null) {
                    return;
                }
                if (event.text == null || event.text.isBlank() || "RESET".equals(event.text)) {
                    customNicks.remove(uuid);
                } else {
                    customNicks.put(uuid, event.text);
                }
                if (redis != null) {
                    redis.persistNick(uuid, event.text);
                }
            }
            default -> {
            }
        }
    }

    /** Don't leak fabric vs paper survival in join/leave text. */
    private static String publicServerName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "network";
        }
        if ("fabric".equalsIgnoreCase(raw) || "survival".equalsIgnoreCase(raw)) {
            return "survival";
        }
        return raw;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String id = event.getIdentifier().getId();

        if (id.equals(CHANNEL_IGNORE.getId())) {
            // Handle ignore updates from backends
            String dataStr = new String(event.getData());
            String[] parts = dataStr.split("\\|");
            if (parts.length != 3) return;

            UUID ignorer = UUID.fromString(parts[0]);
            UUID ignored = UUID.fromString(parts[1]);
            String action = parts[2];

            Set<UUID> set = globalIgnores.computeIfAbsent(ignorer, k -> new HashSet<>());
            if (action.equals("add")) {
                set.add(ignored);
            } else if (action.equals("remove")) {
                set.remove(ignored);
            }
            if (redis != null) {
                redis.persistIgnore(ignorer, ignored, action.equals("add"));
                redis.publishIgnore(ignorer, ignored, action);
            }
            logger.info("Updated ignore for {}: {} {}", ignorer, action, ignored);

        } else if (id.equals(CHANNEL_NICK.getId())) {
            // Handle nick updates from backends
            String dataStr = new String(event.getData());
            String[] parts = dataStr.split("\\|", 2);
            if (parts.length != 2) return;

            UUID uuid = UUID.fromString(parts[0]);
            String nick = parts[1].equals("RESET") ? null : parts[1];

            if (nick == null) {
                customNicks.remove(uuid);
            } else {
                customNicks.put(uuid, nick);
            }
            if (redis != null) {
                redis.persistNick(uuid, nick);
                redis.publishNick(uuid, nick == null ? "RESET" : nick);
            }
            logger.info("Updated nick for {} → {}", uuid, nick == null ? "reset" : nick);
        }
    }
}
