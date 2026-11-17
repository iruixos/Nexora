package com.iruixos.nexora;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** One-time, non-destructive migration of legacy Nexora configurations. */
final class ConfigMigration {
    static final int SCHEMA_VERSION = 2;
    private ConfigMigration() { }

    static boolean migrate(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) return true; // saveDefaultConfig() handles new installs.
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) throw new IOException("Bundled default config.yml is missing");
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            YamlConfiguration existing = new YamlConfiguration();
            existing.load(file);
            int schema = existing.getInt("config-schema-version", 0);
            if (schema > SCHEMA_VERSION) {
                plugin.getLogger().warning("Config schema " + schema + " is newer than this plugin supports; leaving config.yml unchanged.");
                return true;
            }
            List<String> added = new ArrayList<>();
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                // Check explicit keys, not inherited defaults, to avoid silently omitting values.
                if (!existing.contains(key, true)) {
                    existing.set(key, defaults.get(key));
                    added.add(key);
                }
            }
            if (schema == SCHEMA_VERSION && added.isEmpty()) return true;
            // Upgrades from 1.2.3 used real water. Never silently change world-block behaviour.
            if (schema == 0 && !fileContainsExplicitRenderMode(file)) {
                existing.set("portal.render-mode", "WATER");
                added.remove("portal.render-mode");
                plugin.getLogger().warning("Legacy config detected: retaining WATER portal rendering. "
                        + "Switch to EVENT_HORIZON only after backing up gates.db and your world.");
            }
            // Existing deployments may already use a reverse proxy. Keep their
            // configured URL rather than switching them to AUTO unexpectedly.
            if (schema < 2) {
                String url = existing.getString("resource-pack.hosting.public-url", "");
                if (url != null && !url.isBlank() && !url.contains("YOUR-SERVER-IP")) {
                    existing.set("resource-pack.hosting.url-mode", "MANUAL");
                }
            }
            existing.set("config-schema-version", SCHEMA_VERSION);
            Path original = file.toPath();
            String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path backup = original.resolveSibling("config.yml.backup-" + suffix);
            Files.copy(original, backup);
            Path temp = Files.createTempFile(original.getParent(), "config-migration-", ".yml");
            try {
                existing.save(temp.toFile());
                try {
                    Files.move(temp, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temp); }
            plugin.getLogger().info("Migrated config.yml to schema " + SCHEMA_VERSION + "; added "
                    + added.size() + " missing settings. Backup: " + backup.getFileName());
            if (schema == 0) plugin.getLogger().info("Review resource-pack.hosting.public-url to confirm the configured HTTPS endpoint is reachable.");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Config migration failed; original config.yml retained: " + ex.getMessage());
            return false;
        }
    }

    private static boolean fileContainsExplicitRenderMode(File file) throws IOException {
        YamlConfiguration cfg = new YamlConfiguration();
        try { cfg.load(file); } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IOException("Invalid config.yml", ex);
        }
        return cfg.contains("portal.render-mode", true);
    }
}
