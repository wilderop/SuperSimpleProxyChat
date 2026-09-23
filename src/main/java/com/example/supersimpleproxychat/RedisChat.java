package com.example.supersimpleproxychat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentineled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import java.net.URI;
import java.util.UUID;
import java.util.function.Consumer;

final class RedisChat {

    static final String CHANNEL = "sspc:events";
    private static final Gson GSON = new Gson();

    private static final String SENTINEL_MASTER = "azpbmd";
    private static final Set<HostAndPort> SENTINELS = Set.of(
            new HostAndPort("127.0.0.1", 26379),
            new HostAndPort("127.0.0.1", 26379),
            new HostAndPort("127.0.0.1", 26379));

    private final Logger logger;
    private final String proxyId;
    private final HostAndPort hostAndPort;
    private final JedisClientConfig clientConfig;

    private volatile UnifiedJedis pooled;
    private volatile Thread subscriber;
    private volatile boolean running;

    RedisChat(Logger logger, String uri, String proxyId) {
        this.logger = logger;
        this.proxyId = proxyId;
        URI parsed = URI.create(uri);
        String host = parsed.getHost();
        int port = parsed.getPort() > 0 ? parsed.getPort() : 6379;
        String password = null;
        String user = null;
        if (parsed.getUserInfo() != null) {
            String info = parsed.getUserInfo();
            int colon = info.indexOf(':');
            if (colon < 0) {
                password = info;
            } else if (colon == 0) {
                password = info.substring(1);
            } else {
                user = info.substring(0, colon);
                password = info.substring(colon + 1);
            }
        }
        this.hostAndPort = new HostAndPort(host, port);
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .socketTimeoutMillis(3000)
                .connectionTimeoutMillis(3000);
        if (user != null && !user.isBlank()) {
            builder.user(user);
        }
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        this.clientConfig = builder.build();
    }

    void start(Consumer<Event> listener) {
        JedisClientConfig sentinelCfg = DefaultJedisClientConfig.builder()
                .socketTimeoutMillis(3000)
                .connectionTimeoutMillis(3000)
                .build();
        try {
            this.pooled = new JedisSentineled(SENTINEL_MASTER, clientConfig, SENTINELS, sentinelCfg);
            this.pooled.ping();
            logger.info("SuperSimpleProxyChat Redis via Sentinel master={} as {}", SENTINEL_MASTER, proxyId);
        } catch (Exception e) {
            logger.warn("SuperSimpleProxyChat Sentinel failed ({}), falling back to {}:{}",
                    e.getMessage(), hostAndPort.getHost(), hostAndPort.getPort());
            this.pooled = new JedisPooled(hostAndPort, clientConfig);
            try (Jedis jedis = new Jedis(hostAndPort, clientConfig)) {
                jedis.ping();
            }
            logger.info("SuperSimpleProxyChat Redis connected to {}:{} as {}",
                    hostAndPort.getHost(), hostAndPort.getPort(), proxyId);
        }
        this.running = true;
        subscriber = new Thread(() -> subscribeLoop(listener), "sspc-redis");
        subscriber.setDaemon(true);
        subscriber.start();
    }

    void stop() {
        running = false;
        if (subscriber != null) {
            subscriber.interrupt();
        }
        try {
            if (pooled != null) {
                pooled.close();
            }
        } catch (Exception ignored) {
        }
        pooled = null;
    }

    void publishChat(UUID uuid, String name, String server, String formatted) {
        publish("chat", uuid, name, server, formatted, null, null);
    }

    void publishJoin(UUID uuid, String name, String server) {
        publish("join", uuid, name, server, null, null, null);
    }

    void publishLeave(UUID uuid, String name, String server) {
        publish("leave", uuid, name, server, null, null, null);
    }

    void publishIgnore(UUID ignorer, UUID ignored, String action) {
        publish("ignore", ignorer, null, null, null, ignored, action);
    }

    void publishNick(UUID uuid, String nick) {
        publish("nick", uuid, null, null, nick, null, null);
    }

    void persistIgnore(UUID ignorer, UUID ignored, boolean add) {
        if (pooled == null || ignorer == null || ignored == null) {
            return;
        }
        try {
            if (add) {
                pooled.sadd("bch:ignore:" + ignorer, ignored.toString());
            } else {
                pooled.srem("bch:ignore:" + ignorer, ignored.toString());
            }
        } catch (Exception e) {
            logger.warn("Redis persistIgnore failed: {}", e.getMessage());
        }
    }

    void persistNick(UUID uuid, String nick) {
        if (pooled == null || uuid == null) {
            return;
        }
        try {
            if (nick == null || nick.isBlank() || "RESET".equals(nick)) {
                pooled.del("bch:nick:" + uuid);
            } else {
                pooled.set("bch:nick:" + uuid, nick);
            }
        } catch (Exception e) {
            logger.warn("Redis persistNick failed: {}", e.getMessage());
        }
    }

    void loadState(Map<UUID, Set<UUID>> ignores, Map<UUID, String> nicks) {
        if (pooled == null) {
            return;
        }
        try {
            loadByPrefix("bch:ignore:*", key -> {
                String id = key.substring("bch:ignore:".length());
                UUID uuid = UUID.fromString(id);
                Set<UUID> set = ignores.computeIfAbsent(uuid, k -> new HashSet<>());
                for (String raw : pooled.smembers(key)) {
                    try {
                        set.add(UUID.fromString(raw));
                    } catch (Exception ignored) {
                    }
                }
            });
            loadByPrefix("bch:nick:*", key -> {
                String id = key.substring("bch:nick:".length());
                String nick = pooled.get(key);
                if (nick != null && !nick.isBlank()) {
                    nicks.put(UUID.fromString(id), nick);
                }
            });
            logger.info("Loaded {} ignore lists and {} nicks from Redis", ignores.size(), nicks.size());
        } catch (Exception e) {
            logger.warn("Redis loadState failed: {}", e.getMessage());
        }
    }

    private void loadByPrefix(String match, java.util.function.Consumer<String> consumer) {
        ScanParams params = new ScanParams().match(match).count(100);
        String cursor = "0";
        do {
            ScanResult<String> scan = pooled.scan(cursor, params);
            cursor = scan.getCursor();
            for (String key : scan.getResult()) {
                consumer.accept(key);
            }
        } while (!"0".equals(cursor));
    }

    private void publish(String type, UUID uuid, String name, String server,
                         String text, UUID extraUuid, String action) {
        if (pooled == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("proxyId", proxyId);
        if (uuid != null) {
            o.addProperty("uuid", uuid.toString());
        }
        if (name != null) {
            o.addProperty("name", name);
        }
        if (server != null) {
            o.addProperty("server", server);
        }
        if (text != null) {
            o.addProperty("text", text);
        }
        if (extraUuid != null) {
            o.addProperty("extraUuid", extraUuid.toString());
        }
        if (action != null) {
            o.addProperty("action", action);
        }
        try {
            pooled.publish(CHANNEL, GSON.toJson(o));
        } catch (Exception e) {
            logger.warn("Redis publish failed: {}", e.getMessage());
        }
    }

    private void subscribeLoop(Consumer<Event> listener) {
        while (running) {
            try {
                pooled.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (!running) {
                            try {
                                unsubscribe();
                            } catch (Exception ignored) {
                            }
                            return;
                        }
                        try {
                            Event event = GSON.fromJson(message, Event.class);
                            if (event == null || proxyId.equals(event.proxyId)) {
                                return;
                            }
                            listener.accept(event);
                        } catch (Exception e) {
                            logger.debug("Redis chat event ignored: {}", e.getMessage());
                        }
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (running) {
                    logger.warn("Redis chat subscriber disconnected: {}", e.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    static final class Event {
        String type;
        String proxyId;
        String uuid;
        String name;
        String server;
        String text;
        String extraUuid;
        String action;
    }
}
