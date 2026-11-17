package com.iruixos.nexora;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.IDN;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves only Nexora's configured resource-pack ZIP over HTTP.
 * Never exposes arbitrary files or a directory listing.
 */
final class NexoraResourcePack {
    private static final String PACK_NAME = "Nexora-Resources.zip";
    private static final String LEGACY_PACK_NAME = "Nexora-EventHorizon-Pack.zip";
    private final JavaPlugin plugin;
    private HttpServer server;
    private ExecutorService executor;
    private String publicUrl;
    private boolean autoUrl;
    private int hostingPort;
    private byte[] sha1;

    NexoraResourcePack(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) return;
        Path directory = plugin.getDataFolder().toPath().resolve("resourcepack");
        Path zip = directory.resolve(PACK_NAME);
        try {
            Files.createDirectories(directory);
            // Install the optimized pack supplied with this release.
            // Preserve the previous file as a backup before changing its contents.
            Path legacy = directory.resolve(LEGACY_PACK_NAME);
            if (Files.notExists(zip) && Files.exists(legacy)) {
                // Keep the original filename as a legacy HTTP alias during migration.
                // Copy instead of moving so older clients/configured URLs still work.
                plugin.getLogger().info("Migrating previous Nexora resource pack to " + PACK_NAME);
            }
            byte[] bundledBytes;
            try (java.io.InputStream stream = plugin.getResource(PACK_NAME)) {
                if (stream == null) throw new IOException("Bundled resource pack is missing.");
                bundledBytes = stream.readAllBytes();
            }
            if (Files.notExists(zip)) {
                Files.write(zip, bundledBytes);
                plugin.getLogger().info("Installed optimized Nexora resource pack: " + zip);
            } else {
                boolean nexoraPack;
                try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(zip.toFile())) {
                    nexoraPack = archive.getEntry("assets/nexora/models/item/water_curtain.json") != null;
                }
                if (nexoraPack && !java.util.Arrays.equals(Files.readAllBytes(zip), bundledBytes)) {
                    Path backup = directory.resolve("Nexora-Resources.previous.zip");
                    Files.copy(zip, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.write(zip, bundledBytes);
                    plugin.getLogger().info("Updated Nexora resource pack; previous ZIP backed up as " + backup.getFileName());
                } else if (!nexoraPack) {
                    plugin.getLogger().warning("Existing Nexora-Resources.zip is custom/unrecognized; keeping it unchanged.");
                }
            }
            // One-time backup of the previous generated pack name, then remove
            // the obsolete active ZIP so there is only one authoritative pack.
            if (Files.exists(legacy)) {
                Path legacyBackup = directory.resolve("Nexora-EventHorizon-Pack.previous.zip");
                Files.copy(legacy, legacyBackup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.delete(legacy);
                plugin.getLogger().info("Archived obsolete resource pack as " + legacyBackup.getFileName());
            }
            byte[] contents = Files.readAllBytes(zip);
            if (contents.length == 0 || contents.length > 32 * 1024 * 1024) {
                throw new IOException("Resource-pack ZIP is empty or exceeds 32 MiB.");
            }
            try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(zip.toFile())) {
                if (archive.getEntry("pack.mcmeta") == null) {
                    throw new IOException("Resource-pack ZIP has no pack.mcmeta at its root.");
                }
            }
            sha1 = MessageDigest.getInstance("SHA-1").digest(contents);
            String bind = plugin.getConfig().getString("resource-pack.hosting.bind-address", "0.0.0.0");
            int port = plugin.getConfig().getInt("resource-pack.hosting.port", 25778);
            hostingPort = port;
            String mode = plugin.getConfig().getString("resource-pack.hosting.url-mode", "MANUAL");
            autoUrl = "AUTO".equalsIgnoreCase(mode);
            if (!autoUrl && !"MANUAL".equalsIgnoreCase(mode)) {
                throw new IOException("resource-pack.hosting.url-mode must be AUTO or MANUAL");
            }
            publicUrl = plugin.getConfig().getString("resource-pack.hosting.public-url", "https://resources.iruixos.com/Nexora-Resources.zip");
            if (publicUrl != null) publicUrl = publicUrl.trim();
            if (!autoUrl && !validUrl(publicUrl)) {
                plugin.getLogger().warning("MANUAL mode requires a valid HTTP/HTTPS public-url ending in /" + PACK_NAME);
                publicUrl = null;
            }
            if (!plugin.getConfig().getBoolean("resource-pack.hosting.enabled", true)) {
                if (autoUrl) {
                    plugin.getLogger().warning("AUTO URL mode requires Nexora's integrated HTTP hosting to be enabled.");
                    autoUrl = false;
                    publicUrl = null;
                }
                plugin.getLogger().info("Integrated HTTP hosting disabled; configured external pack URL will be used if valid.");
                return;
            }
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            final byte[] packBytes = contents;
            server.createContext("/" + PACK_NAME, exchange -> serve(exchange, packBytes));
            // Compatibility alias for existing config.yml public-url values.
            server.createContext("/" + LEGACY_PACK_NAME, exchange -> serve(exchange, packBytes));
            executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "Nexora-Pack-HTTP");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Resource-pack HTTP listener started on " + bind + ":" + port
                    + " (SHA-1 " + HexFormat.of().formatHex(sha1) + ")");
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not start Nexora resource-pack hosting: " + ex.getMessage());
            stop();
        }
    }

    private static boolean validUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && host != null && !host.equalsIgnoreCase("YOUR-SERVER-IP")
                    && !host.equals("0.0.0.0")
                    && (("/" + PACK_NAME).equals(uri.getRawPath())
                        || ("/" + LEGACY_PACK_NAME).equals(uri.getRawPath()))
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void serve(HttpExchange exchange, byte[] data) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(data.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        } finally {
            exchange.close();
        }
    }

    void send(Player player) {
        if (sha1 == null) return;
        if (!plugin.getConfig().getBoolean("resource-pack.delivery.send-on-join", true)) return;
        String prompt = plugin.getConfig().getString("resource-pack.delivery.prompt",
                "Download Nexora's animated portal textures?");
        boolean required = plugin.getConfig().getBoolean("resource-pack.delivery.required", false);
        String url = autoUrl ? autoUrlFor(player) : publicUrl;
        if (url == null) return;
        player.setResourcePack(url, sha1.clone(), prompt, required);
    }

    /** Derive a client-visible HTTP hostname from the hostname used to join Minecraft.
     *  AUTO cannot infer firewall, port-forwarding or reverse-proxy configuration.
     */
    private String autoUrlFor(Player player) {
        if (server == null) return null;
        InetSocketAddress virtualHost = player.getVirtualHost();
        if (virtualHost == null) {
            plugin.getLogger().warning("Cannot determine Minecraft join hostname for " + player.getName()
                    + "; set resource-pack.hosting.url-mode to MANUAL.");
            return null;
        }
        String host = virtualHost.getHostString();
        if (host == null || host.isBlank() || host.equalsIgnoreCase("localhost")
                || host.equals("0.0.0.0") || host.contains("/") || host.contains("@")) return null;
        // Hostnames supplied during the Minecraft handshake are untrusted; reject
        // characters that could turn a hostname into an arbitrary URL.
        if (!host.matches("[A-Za-z0-9.\\-:]+") || host.length() > 253) return null;
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        String url = "http://" + host + ":" + hostingPort + "/" + PACK_NAME;
        return validUrl(url) ? url : null;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        sha1 = null;
        publicUrl = null;
        autoUrl = false;
    }
}
