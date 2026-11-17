package com.iruixos.nexora;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * EXPERIMENTAL: client-only upper-frame water neighbours plus visual block replicas.
 * Real frame blocks are never replaced: server collision and gate validation remain intact.
 * Client-side collision prediction, mining and interaction can disagree with the server.
 */
final class WaterFrameExperiment {
    private final NexoraPlugin plugin;
    private final Map<UUID, List<BlockDisplay>> displays = new HashMap<>();
    private final Map<UUID, Map<Location, BlockData>> spoofed = new HashMap<>();
    private final Map<UUID, Map<Location, BlockData>> desired = new HashMap<>();
    private final Map<UUID, Set<Location>> gateLocations = new HashMap<>();
    private final Map<UUID, Set<Location>> manualPreviews = new HashMap<>();
    private int lastEligibleGates, lastRejectedGates, lastWaterPackets;
    // Avoid re-sending the same fake block every 10 ticks. Reapply occasionally for chunk refreshes.
    private long tickCount;
    private String appliedRenderSignature = "";
    private int refreshCounter;
    private final Map<UUID, Set<Location>> collisionSafe = new HashMap<>();
    private final BlockData water = Bukkit.createBlockData(Material.WATER);

    WaterFrameExperiment(NexoraPlugin plugin) { this.plugin = plugin; }

    private String lightingMode() {
        String mode = plugin.getConfig().getString(
                "portal.experimental-water-frame.rendering.lighting-mode", "VANILLA");
        return "OVERRIDE".equalsIgnoreCase(mode) ? "OVERRIDE" : "VANILLA";
    }

    private float displayViewRange() {
        return (float) Math.max(1.0, Math.min(16.0, plugin.getConfig().getDouble(
                "portal.experimental-water-frame.rendering.display-view-range", 8.0)));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("portal.experimental-water-frame.enabled", false);
    }

    void refresh(Collection<Gate> gates) {
        if (!enabled()) {
            clear();
            return;
        }
        String signature = lightingMode() + ":" + plugin.getConfig().getInt(
                "portal.experimental-water-frame.rendering.override-block-light", 15) + ":"
                + plugin.getConfig().getInt("portal.experimental-water-frame.rendering.override-sky-light", 15)
                + ":" + displayViewRange();
        if (!signature.equals(appliedRenderSignature)) {
            clear();
            appliedRenderSignature = signature;
        }
        Set<UUID> valid = new HashSet<>();
        lastEligibleGates=0;lastRejectedGates=0;
        for (Gate gate : gates) {
            valid.add(gate.id);
            if (displays.containsKey(gate.id)) {
                if (displays.get(gate.id).stream().allMatch(BlockDisplay::isValid)) {
                    lastEligibleGates++;
                    continue;
                }
                remove(gate.id);
            }
            World world = gate.world();
            if (world == null || gate.cells.isEmpty()) continue;
            int top = gate.cells.stream().mapToInt(c -> Gate.coords(c)[1]).max().orElse(Integer.MIN_VALUE);
            Map<Location, BlockData> frame = new LinkedHashMap<>();
            boolean loaded = true;
            for (String cell : gate.cells) {
                int[] xyz = Gate.coords(cell);
                if (xyz[1] != top) continue;
                int y = xyz[1] + 1;
                if (!world.isChunkLoaded(xyz[0] >> 4, xyz[2] >> 4)) { loaded = false; break; }
                Block block = world.getBlockAt(xyz[0], y, xyz[2]);
                if (!block.getType().isSolid()) { loaded = false; break; }
                frame.put(block.getLocation(), block.getBlockData().clone());
            }
            if (!loaded || frame.isEmpty()) { lastRejectedGates++; continue; }
            lastEligibleGates++;
            List<BlockDisplay> spawned = new ArrayList<>();
            for (var entry : frame.entrySet()) {
                Location pos = entry.getKey();
                BlockData model = entry.getValue();
                // Default to Minecraft's display lighting instead of a fixed brightness
                // sampled from a different block position. OVERRIDE is diagnostic only.
                String lighting = lightingMode();
                Display.Brightness brightness = null;
                if ("OVERRIDE".equals(lighting)) {
                    int blockLight = Math.max(0, Math.min(15, plugin.getConfig().getInt(
                            "portal.experimental-water-frame.rendering.override-block-light", 15)));
                    int skyLight = Math.max(0, Math.min(15, plugin.getConfig().getInt(
                            "portal.experimental-water-frame.rendering.override-sky-light", 15)));
                    brightness = new Display.Brightness(blockLight, skyLight);
                }
                final Display.Brightness finalBrightness = brightness;
                BlockDisplay display = world.spawn(pos, BlockDisplay.class, entity -> {
                    entity.setBlock(model);
                    entity.setTransformation(new Transformation(
                            new Vector3f(0f, 0f, 0f),
                            new Quaternionf(), new Vector3f(1f, 1f, 1f), new Quaternionf()));
                    entity.setBrightness(finalBrightness);
                    entity.setPersistent(false);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setViewRange(displayViewRange());
                    // Stable culling volume, independent of player movement.
                    entity.setDisplayWidth(1.5f);
                    entity.setDisplayHeight(1.5f);
                });
                spawned.add(display);
            }
            displays.put(gate.id, spawned);
            gateLocations.put(gate.id, new HashSet<>(frame.keySet()));
            desired.put(gate.id, frame);
        }
        for (UUID id : new ArrayList<>(displays.keySet())) if (!valid.contains(id)) remove(id);
    }

    void tick(Collection<Gate> gates) {
        refresh(gates);
        if (!enabled()) return;
        // Do not send fake water beyond the configured range; the replacement displays
        // have a higher view-range multiplier to avoid disappearing first.
        double radius = Math.max(4.0, plugin.getConfig().getDouble("portal.experimental-water-frame.view-distance", 24.0));
        double radiusSq = radius * radius;
        lastWaterPackets=0;
        tickCount++;
        // Re-send periodically, rather than on every tick. This avoids continual
        // block render rebuilds and reduces visual flicker when the camera moves.
        boolean periodicResync = tickCount % 10 == 0; // 5 seconds at 10-tick scheduling
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<Location, BlockData> wanted = new HashMap<>();
            Location playerPos = player.getLocation();
            for (Map<Location, BlockData> gateFrame : desired.values()) {
                for (var entry : gateFrame.entrySet()) {
                    Location loc = entry.getKey();
                    if (loc.getWorld() != player.getWorld() || loc.distanceSquared(playerPos) > radiusSq) continue;
                    if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
                    // Never spoof a changed/broken frame; validation will remove the gate.
                    if (!loc.getBlock().getBlockData().matches(entry.getValue())) continue;
                    // Client collision must agree with the real solid frame when a player
                    // is on or immediately above it. Use hysteresis to prevent rapid toggling.
                    if (nearTopFrame(playerPos, loc, player.getUniqueId())) continue;
                    wanted.put(loc, entry.getValue());
                }
            }
            Map<Location, BlockData> old = spoofed.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
            for (Location loc : new ArrayList<>(old.keySet())) {
                if (wanted.containsKey(loc)) continue;
                restore(player, loc);
                old.remove(loc);
            }
            for (var entry : wanted.entrySet()) {
                Location loc=entry.getKey();
                if(manualPreviews.getOrDefault(player.getUniqueId(),Set.of()).contains(loc))continue;
                if (!old.containsKey(loc) || periodicResync) {
                    player.sendBlockChange(loc, water);
                    lastWaterPackets++;
                }
                old.put(loc, entry.getValue());
            }
            if (old.isEmpty()) spoofed.remove(player.getUniqueId());
        }
    }

    /**
     * Player-local collision protection. Keep actual frame visible when the player
     * is standing on/near its top, so the client predicts solid collision.
     * Hysteresis avoids rapidly alternating states near the boundary.
     */
    private boolean nearTopFrame(Location player, Location block, UUID playerId) {
        double x = player.getX() - (block.getBlockX() + 0.5);
        double z = player.getZ() - (block.getBlockZ() + 0.5);
        double y = player.getY() - block.getBlockY();
        Set<Location> protectedBlocks = collisionSafe.computeIfAbsent(playerId, k -> new HashSet<>());
        boolean wasProtected = protectedBlocks.contains(block);
        double horizontal = wasProtected ? 2.0 : 1.35;
        boolean protect = Math.abs(x) <= horizontal && Math.abs(z) <= horizontal
                && y >= (wasProtected ? -0.8 : -0.3) && y <= (wasProtected ? 3.0 : 2.4);
        if (protect) protectedBlocks.add(block);
        else protectedBlocks.remove(block);
        return protect;
    }

    private void restore(Player player, Location loc) {
        if (player.isOnline() && loc.getWorld() == player.getWorld()
                && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    void remove(UUID gateId) {
        List<BlockDisplay> entities = displays.remove(gateId);
        if (entities != null) for (BlockDisplay entity : entities) if (entity.isValid()) entity.remove();
        Set<Location> locations = gateLocations.remove(gateId);
        desired.remove(gateId);
        if (locations != null) for (Player player : Bukkit.getOnlinePlayers()) {
            Map<Location, BlockData> old = spoofed.get(player.getUniqueId());
            if (old == null) continue;
            for (Location loc : locations) if (old.remove(loc) != null) restore(player, loc);
            if (old.isEmpty()) spoofed.remove(player.getUniqueId());
        }
    }

    void forget(Player player) { spoofed.remove(player.getUniqueId());manualPreviews.remove(player.getUniqueId());collisionSafe.remove(player.getUniqueId()); }
    void suspendPreview(Player player,Set<Location> locations){
        manualPreviews.computeIfAbsent(player.getUniqueId(),ignored->new HashSet<>()).addAll(locations);
    }
    void resumePreview(Player player,Set<Location> locations){
        Set<Location> active=manualPreviews.get(player.getUniqueId());
        if(active!=null){active.removeAll(locations);if(active.isEmpty())manualPreviews.remove(player.getUniqueId());}
    }
    List<String> debugLines(){
        int displayCount=displays.values().stream().mapToInt(List::size).sum();
        int spoofCount=spoofed.values().stream().mapToInt(Map::size).sum();
        return List.of("Tracked gates: "+desired.size()+" | Display entities: "+displayCount,
                "Eligible this refresh: "+lastEligibleGates+" | Skipped: "+lastRejectedGates,
                "Client water positions: "+spoofCount+" | Packets last tick: "+lastWaterPackets,
                "Manual preview players: "+manualPreviews.size(),
                "Collision-safe players: "+collisionSafe.values().stream().filter(v -> !v.isEmpty()).count(),
                "Lighting: "+lightingMode()+" | Display range multiplier: "+displayViewRange(),
                "Water resync interval: "+plugin.getConfig().getInt(
                    "portal.experimental-water-frame.rendering.refresh-interval-ticks",200)+" ticks");
    }

    void clear() {
        for (UUID id : new ArrayList<>(displays.keySet())) remove(id);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<Location, BlockData> old = spoofed.remove(player.getUniqueId());
            if (old != null) for (Location loc : old.keySet()) restore(player, loc);
        }
        desired.clear();
        gateLocations.clear();
        manualPreviews.clear();
        collisionSafe.clear();
    }
}
