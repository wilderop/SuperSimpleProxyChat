package com.wilderop.proxychat;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Plugin(
        id = "supersimpleproxychat",
        name = "Super Simple Proxy Chat",
        version = "1.0.0",
        description = "Cross-server chat with nicks, ignores, and networked /msg mail",
        authors = {"wilderop"}
)
public class SuperSimpleProxyChat {

    public static final ChannelIdentifier CHANNEL_IGNORE = MinecraftChannelIdentifier.create("backchat", "ignore");
    public static final ChannelIdentifier CHANNEL_NICK = MinecraftChannelIdentifier.create("backchat", "nick");
    public static final ChannelIdentifier CHANNEL_MAIL = MinecraftChannelIdentifier.create("backchat", "mail");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private String chatFormat = "<{nick}> {message}";

    private final Map<UUID, Set<UUID>> globalIgnores = new HashMap<>();
    private final Map<UUID, String> customNicks = new HashMap<>();
    private final Map<String, UUID> knownNames = new HashMap<>();
    private final Map<UUID, List<QueuedMail>> mailbox = new HashMap<>();

    @Inject
    public SuperSimpleProxyChat(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        loadKnownNames();
        loadMailbox();
        server.getChannelRegistrar().register(CHANNEL_IGNORE, CHANNEL_NICK, CHANNEL_MAIL);
        logger.info("Super Simple Proxy Chat enabled.");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        saveKnownNames();
        saveMailbox();
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        knownNames.put(player.getUsername().toLowerCase(Locale.ROOT), player.getUniqueId());
        saveKnownNames();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        saveMailbox();
        saveKnownNames();
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        deliverMail(event.getPlayer());
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        Player sender = event.getPlayer();
        String message = event.getMessage();
        String nick = displayName(sender);
        String formatted = chatFormat
                .replace("{player}", sender.getUsername())
                .replace("{nick}", nick)
                .replace("{message}", message);

        broadcastToOtherServers(sender, formatted);
    }

    private void broadcastToOtherServers(Player sender, String text) {
        Component component = MiniMessage.miniMessage().deserialize(text);
        String senderServer = currentServerName(sender);
        UUID senderUuid = sender.getUniqueId();

        for (Player online : server.getAllPlayers()) {
            String onlineServer = currentServerName(online);
            if (onlineServer == null || senderServer == null) {
                continue;
            }
            if (onlineServer.equals(senderServer)) {
                continue;
            }
            Set<UUID> ignored = globalIgnores.getOrDefault(online.getUniqueId(), Collections.emptySet());
            if (ignored.contains(senderUuid)) {
                continue;
            }
            online.sendMessage(component);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier id = event.getIdentifier();
        String payload = new String(event.getData(), StandardCharsets.UTF_8);

        if (id.equals(CHANNEL_IGNORE)) {
            handleIgnore(payload);
        } else if (id.equals(CHANNEL_NICK)) {
            handleNick(payload);
        } else if (id.equals(CHANNEL_MAIL)) {
            handleMail(payload);
        }
    }

    private void handleIgnore(String payload) {
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) {
            return;
        }
        try {
            UUID ignorer = UUID.fromString(parts[0]);
            UUID ignored = UUID.fromString(parts[1]);
            Set<UUID> set = globalIgnores.computeIfAbsent(ignorer, k -> new HashSet<>());
            if ("add".equalsIgnoreCase(parts[2])) {
                set.add(ignored);
            } else {
                set.remove(ignored);
            }
        } catch (IllegalArgumentException ignored) {
            logger.warn("Bad ignore payload: {}", payload);
        }
    }

    private void handleNick(String payload) {
        String[] parts = payload.split("\\|", 2);
        if (parts.length != 2) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(parts[0]);
            if ("RESET".equals(parts[1]) || parts[1].isEmpty()) {
                customNicks.remove(uuid);
            } else {
                customNicks.put(uuid, parts[1]);
            }
        } catch (IllegalArgumentException ignored) {
            logger.warn("Bad nick payload: {}", payload);
        }
    }

    private void handleMail(String payload) {
        String[] parts = payload.split("\\|", 5);
        if (parts.length < 5 || !"SEND".equals(parts[0])) {
            return;
        }

        UUID from;
        try {
            from = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return;
        }
        String fromName = parts[2];
        String targetName = parts[3];
        String message = parts[4];

        Optional<Player> senderOpt = server.getPlayer(from);
        Optional<Player> targetOnline = findOnline(targetName);
        UUID targetUuid = targetOnline.map(Player::getUniqueId)
                .orElse(knownNames.get(targetName.toLowerCase(Locale.ROOT)));

        if (targetUuid == null) {
            replyBackend(senderOpt.orElse(null), "ACK_FAIL|Player not found: " + targetName);
            return;
        }

        Set<UUID> ignoredByTarget = globalIgnores.getOrDefault(targetUuid, Collections.emptySet());
        if (ignoredByTarget.contains(from)) {
            replyBackend(senderOpt.orElse(null), "ACK_FAIL|You cannot message someone who ignores you.");
            return;
        }

        Component toTarget = MiniMessage.miniMessage().deserialize(
                "<gray>[<aqua>" + escapeMini(fromName) + "</aqua> → me]:</gray> <white>"
                        + escapeMini(message) + "</white>");

        if (targetOnline.isPresent()) {
            Player target = targetOnline.get();
            target.sendMessage(toTarget);
            sendToPlayerBackend(target, CHANNEL_MAIL, "LASTREPLY|" + from);
            replyBackend(senderOpt.orElse(null), "ACK_SENT|" + target.getUsername() + "|" + message);
        } else {
            mailbox.computeIfAbsent(targetUuid, k -> new ArrayList<>())
                    .add(new QueuedMail(from, fromName, message, System.currentTimeMillis()));
            saveMailbox();
            String display = displayNameFor(targetUuid, targetName);
            replyBackend(senderOpt.orElse(null), "ACK_QUEUED|" + display + "|" + message);
        }
    }

    private void deliverMail(Player player) {
        List<QueuedMail> inbox = mailbox.remove(player.getUniqueId());
        if (inbox == null || inbox.isEmpty()) {
            return;
        }
        saveMailbox();

        player.sendMessage(Component.text("You have " + inbox.size() + " offline message(s):", NamedTextColor.GOLD));
        UUID lastFrom = null;
        for (QueuedMail mail : inbox) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>[<aqua>" + escapeMini(mail.fromName) + "</aqua> → me]:</gray> <white>"
                            + escapeMini(mail.message) + "</white>"));
            lastFrom = mail.from;
        }
        if (lastFrom != null) {
            sendToPlayerBackend(player, CHANNEL_MAIL, "LASTREPLY|" + lastFrom);
        }
    }

    private void replyBackend(Player sender, String payload) {
        if (sender == null) {
            return;
        }
        sendToPlayerBackend(sender, CHANNEL_MAIL, payload);
    }

    private void sendToPlayerBackend(Player player, ChannelIdentifier channel, String payload) {
        player.getCurrentServer().ifPresent(conn ->
                conn.sendPluginMessage(channel, payload.getBytes(StandardCharsets.UTF_8)));
    }

    private Optional<Player> findOnline(String name) {
        Optional<Player> exact = server.getPlayer(name);
        if (exact.isPresent()) {
            return exact;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Player player : server.getAllPlayers()) {
            if (player.getUsername().equalsIgnoreCase(lower)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    private String displayName(Player player) {
        return customNicks.getOrDefault(player.getUniqueId(), player.getUsername());
    }

    private String displayNameFor(UUID uuid, String fallback) {
        return customNicks.getOrDefault(uuid, fallback);
    }

    private String currentServerName(Player player) {
        return player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(null);
    }

    private String escapeMini(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    } else {
                        Files.writeString(configPath, "chat-format: \"<{nick}> {message}\"\n");
                    }
                }
            }
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(configPath)) {
                Object loaded = yaml.load(in);
                if (loaded instanceof Map<?, ?> map) {
                    Object format = map.get("chat-format");
                    if (format != null) {
                        chatFormat = String.valueOf(format).trim();
                    }
                }
            }
            logger.info("Chat format: {}", chatFormat);
        } catch (IOException e) {
            logger.warn("Failed to load config.yml", e);
        }
    }

    private void loadKnownNames() {
        Path file = dataDirectory.resolve("known-names.txt");
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    knownNames.put(parts[0].toLowerCase(Locale.ROOT), UUID.fromString(parts[1].trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load known-names.txt", e);
        }
    }

    private void saveKnownNames() {
        Path file = dataDirectory.resolve("known-names.txt");
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : knownNames.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        try {
            Files.createDirectories(dataDirectory);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to save known-names.txt", e);
        }
    }

    private void loadMailbox() {
        Path file = dataDirectory.resolve("mail.txt");
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\|", 5);
                if (parts.length != 5) {
                    continue;
                }
                try {
                    UUID to = UUID.fromString(parts[0]);
                    UUID from = UUID.fromString(parts[1]);
                    String fromName = parts[2];
                    long time = Long.parseLong(parts[3]);
                    String message = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                    mailbox.computeIfAbsent(to, k -> new ArrayList<>())
                            .add(new QueuedMail(from, fromName, message, time));
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load mail.txt", e);
        }
    }

    private void saveMailbox() {
        Path file = dataDirectory.resolve("mail.txt");
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, List<QueuedMail>> entry : mailbox.entrySet()) {
            for (QueuedMail mail : entry.getValue()) {
                String encoded = Base64.getEncoder().encodeToString(mail.message.getBytes(StandardCharsets.UTF_8));
                lines.add(entry.getKey() + "|" + mail.from + "|" + mail.fromName + "|" + mail.time + "|" + encoded);
            }
        }
        try {
            Files.createDirectories(dataDirectory);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to save mail.txt", e);
        }
    }

    private static final class QueuedMail {
        private final UUID from;
        private final String fromName;
        private final String message;
        private final long time;

        private QueuedMail(UUID from, String fromName, String message, long time) {
            this.from = from;
            this.fromName = fromName;
            this.message = message;
            this.time = time;
        }
    }
}
