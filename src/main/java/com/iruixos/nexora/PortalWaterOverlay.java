package com.iruixos.nexora;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** Experimental visual-only upper-water seam cover. No world blocks are changed. */
final class PortalWaterOverlay {
    private final NexoraPlugin plugin;
    private final Map<UUID, List<BlockDisplay>> active = new HashMap<>();

    PortalWaterOverlay(NexoraPlugin plugin) { this.plugin = plugin; }

    void sync(Gate gate) {
        if (true) { // Retired in dev.5; leave old config key harmless
            remove(gate.id);
            return;
        }
        if (active.containsKey(gate.id)) return;
        World world = gate.world();
        if (world == null || gate.cells.isEmpty()) return;
        int highest = gate.cells.stream().mapToInt(cell -> Gate.coords(cell)[1]).max().orElse(Integer.MIN_VALUE);
        List<BlockDisplay> displays = new ArrayList<>();
        for (String cell : gate.cells) {
            int[] xyz = Gate.coords(cell);
            if (xyz[1] != highest || !world.isChunkLoaded(xyz[0] >> 4, xyz[2] >> 4)) {
                if (xyz[1] == highest) { for (BlockDisplay display : displays) display.remove(); return; }
                continue;
            }
            Block block = world.getBlockAt(xyz[0], xyz[1], xyz[2]);
            if (block.getType() != Material.WATER) continue;
            // A thin, translucent horizontal strip over the exposed upper water edge.
            // Deliberately inset into the portal plane to avoid protruding through the frame.
            Location loc = new Location(world, xyz[0], xyz[1], xyz[2]);
            BlockDisplay display = world.spawn(loc, BlockDisplay.class, entity -> {
                entity.setBlock(Bukkit.createBlockData(Material.LIGHT_BLUE_STAINED_GLASS));
                Vector3f translation = gate.plane.equals("X")
                        ? new Vector3f(0f, 0.875f, 0.46f)
                        : new Vector3f(0.46f, 0.875f, 0f);
                Vector3f scale = gate.plane.equals("X")
                        ? new Vector3f(1f, 0.125f, 0.08f)
                        : new Vector3f(0.08f, 0.125f, 1f);
                entity.setTransformation(new Transformation(translation, new Quaternionf(), scale, new Quaternionf()));
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setViewRange(0.75f);
            });
            displays.add(display);
        }
        if (!displays.isEmpty()) active.put(gate.id, displays);
    }

    void remove(UUID id) {
        List<BlockDisplay> displays = active.remove(id);
        if (displays != null) for (BlockDisplay display : displays) if (display.isValid()) display.remove();
    }

    void clear() {
        for (UUID id : new ArrayList<>(active.keySet())) remove(id);
    }

    void syncLoaded(Collection<Gate> gates) {
        Set<UUID> ids = new HashSet<>();
        for (Gate gate : gates) {
            ids.add(gate.id);
            List<BlockDisplay> displays = active.get(gate.id);
            if (displays != null && displays.stream().anyMatch(display -> !display.isValid())) remove(gate.id);
            sync(gate);
        }
        for (UUID id : new ArrayList<>(active.keySet())) if (!ids.contains(id)) remove(id);
    }
}
