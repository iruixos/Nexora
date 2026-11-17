package com.iruixos.nexora;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Per-player visual fallback based on Nexora 1.2.3's water-filled portal.
 * Sends water block appearances only: the actual portal remains air on the server.
 */
final class LegacyPortalFallback implements Listener {
    private enum State { PENDING, LOADED, FALLBACK }
    private final JavaPlugin plugin;
    private final NexoraPlugin nexora;
    private final EventHorizonRenderer horizon;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Set<org.bukkit.Location>> spoofed = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private int cycle = 0;
    private final BlockData water = Bukkit.createBlockData(Material.WATER);

    LegacyPortalFallback(NexoraPlugin nexora, EventHorizonRenderer horizon) {
        this.plugin = nexora;
        this.nexora = nexora;
        this.horizon = horizon;
    }
    private boolean enabled() {
        return plugin.getConfig().getString("resource-pack.fallback.mode", "LEGACY")
                .equalsIgnoreCase("LEGACY")
                && plugin.getConfig().getString("portal.render-mode", "WATER")
                .equalsIgnoreCase("EVENT_HORIZON");
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        states.put(player.getUniqueId(), State.PENDING);
        int seconds = Math.max(5, plugin.getConfig().getInt("resource-pack.fallback.timeout-seconds", 20));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (states.get(player.getUniqueId()) == State.PENDING) {
                states.put(player.getUniqueId(), State.FALLBACK);
                refresh(player);
            }
        }, 40L + seconds * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) refresh(player);
        }, 5L);
    }
    @EventHandler public void status(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        String result = event.getStatus().name();
        if ("SUCCESSFULLY_LOADED".equals(result)) {
            states.put(player.getUniqueId(), State.LOADED);
        } else if (Set.of("DECLINED", "FAILED_DOWNLOAD", "FAILED_RELOAD",
                "INVALID_URL", "DISCARDED").contains(result)) {
            states.put(player.getUniqueId(), State.FALLBACK);
        } else return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) refresh(player);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        spoofed.remove(event.getPlayer().getUniqueId());
        dirty.remove(event.getPlayer().getUniqueId());
    }
    @EventHandler public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        dirty.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) refresh(player);
        }, 3L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) refresh(player);
        }, 12L);
    }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        dirty.add(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) refresh(event.getPlayer());
        }, 5L);
    }
    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        // A chunk reload invalidates all previous client-side block appearances.
        // Reconcile only players in the same world and close to this chunk.
        for (Player player : event.getWorld().getPlayers()) {
            if (Math.abs((player.getLocation().getBlockX() >> 4) - event.getChunk().getX()) > 5
                    || Math.abs((player.getLocation().getBlockZ() >> 4) - event.getChunk().getZ()) > 5) continue;
            dirty.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) refresh(player);
            }, 3L);
        }
    }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent event) {
        // Don't retain stale client block-change state for unloaded chunks.
        for (Set<org.bukkit.Location> locations : spoofed.values()) {
            locations.removeIf(loc -> loc.getWorld() == event.getWorld()
                    && (loc.getBlockX() >> 4) == event.getChunk().getX()
                    && (loc.getBlockZ() >> 4) == event.getChunk().getZ());
        }
    }
    void onDisplaySpawn(Entity display) {
        // Critical: hide the new display before clients without the pack see it.
        for (Player player : display.getWorld().getPlayers()) {
            if (shouldFallback(player)) player.hideEntity(plugin, display);
        }
    }
    void tick() {
        // Periodic forced reconciliation also repairs client-side block changes
        // discarded by client chunk streaming (which has no Bukkit event).
        boolean force = (++cycle % 5) == 0;
        for (Player player : Bukkit.getOnlinePlayers()) sync(player, force || dirty.remove(player.getUniqueId()));
    }
    void refresh(Player player) {
        dirty.remove(player.getUniqueId());
        sync(player, true);
    }
    private boolean shouldFallback(Player player) {
        return enabled() && states.getOrDefault(player.getUniqueId(), State.PENDING) != State.LOADED;
    }
    private void sync(Player player, boolean force) {
        boolean fallback = shouldFallback(player);
        // Hide the pack-dependent model from clients that have not loaded the pack.
        for (Entity display : horizon.entities()) {
            if (fallback) player.hideEntity(plugin, display);
            else player.showEntity(plugin, display);
        }
        Set<org.bukkit.Location> desired = new HashSet<>();
        if (fallback) {
            double range = Math.max(8, plugin.getConfig().getDouble("resource-pack.fallback.view-distance", 48));
            double maxSq = range * range;
            for (Gate gate : nexora.fallbackGates()) {
                if (gate.world() == null || !gate.world().equals(player.getWorld())) continue;
                for (String key : gate.cells) {
                    Block block = gate.block(key);
                    if (block == null || !block.getChunk().isLoaded() || !block.getType().isAir()) continue;
                    org.bukkit.Location loc = block.getLocation();
                    if (loc.distanceSquared(player.getLocation()) <= maxSq) desired.add(loc);
                }
            }
        }
        Set<org.bukkit.Location> old = spoofed.getOrDefault(player.getUniqueId(), Set.of());
        for (org.bukkit.Location loc : old) {
            if (!desired.contains(loc) && loc.getWorld() != null
                    && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }
        for (org.bukkit.Location loc : desired) {
            if (force || !old.contains(loc)) player.sendBlockChange(loc, water);
        }
        spoofed.put(player.getUniqueId(), desired);
    }
    void reset() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<org.bukkit.Location> old = spoofed.remove(player.getUniqueId());
            if (old != null) for (org.bukkit.Location loc : old) {
                if (loc.getWorld() != null && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4))
                    player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
            for (Entity display : horizon.entities()) player.showEntity(plugin, display);
        }
    }
}
