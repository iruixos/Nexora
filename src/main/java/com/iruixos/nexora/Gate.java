package com.iruixos.nexora;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import java.util.*;

final class Gate {
    final UUID id, worldId, owner;
    final String networkKey, networkDisplay;
    final String plane;
    private BlockFace preferredExit;
    final List<String> cells;
    final Map<String,String> originals;
    Gate(UUID id, UUID worldId, UUID owner, String networkKey, String networkDisplay, String plane, List<String> cells, Map<String,String> originals, BlockFace preferredExit) {
        this.id=id; this.worldId=worldId; this.owner=owner; this.networkKey=networkKey; this.networkDisplay=networkDisplay;
        this.plane=plane; this.cells=List.copyOf(cells); this.originals=Map.copyOf(originals);
        this.preferredExit=preferredExit;
    }
    BlockFace preferredExit(){return preferredExit;}
    void setPreferredExit(BlockFace face){preferredExit=face;}
    boolean acceptsExit(BlockFace face){
        return face != null && (plane.equals("X")
            ? face==BlockFace.NORTH || face==BlockFace.SOUTH
            : face==BlockFace.EAST || face==BlockFace.WEST);
    }
    // Infer the activation side from the player's position, not their yaw.
    // For a portal spanning X, its normal is north/south; for Z, east/west.
    BlockFace sideFrom(Location location){
        double cx=0,cz=0;
        for(String cell:cells){int[] c=coords(cell);cx+=c[0]+0.5;cz+=c[2]+0.5;}
        cx/=cells.size();cz/=cells.size();
        if(plane.equals("X"))return location.getZ()>=cz?BlockFace.SOUTH:BlockFace.NORTH;
        return location.getX()>=cx?BlockFace.EAST:BlockFace.WEST;
    }
    static float exitYaw(BlockFace face){
        return switch(face){case NORTH->180f;case SOUTH->0f;case EAST->-90f;case WEST->90f;
            default->throw new IllegalArgumentException("Non-horizontal exit direction");};
    }
    static String key(Block b) {return b.getX()+","+b.getY()+","+b.getZ();}
    static int[] coords(String s) {String[] p=s.split(",");return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};}
    World world(){return Bukkit.getWorld(worldId);}
    Block block(String s){World w=world();if(w==null)return null;int[] c=coords(s);return w.getBlockAt(c[0],c[1],c[2]);}
    boolean contains(Block b){return b.getWorld().getUID().equals(worldId)&&cells.contains(key(b));}
    Location arrival(){
        World w=world();
        if(w==null || cells.isEmpty())return null;
        // A stored preferred exit is authoritative. Never fall back to the
        // opposite face when the chosen side has no safe landing.
        BlockFace defaultSide = plane.equals("X") ? BlockFace.SOUTH : BlockFace.EAST;
        BlockFace[] sides = preferredExit == null
                ? new BlockFace[]{defaultSide, defaultSide.getOppositeFace()}
                : new BlockFace[]{preferredExit};
        // Search the bottom edge first: elevated gates should land on the
        // correct side one block below rather than select a high floating exit.
        List<int[]> positions = new ArrayList<>();
        for (String cell : cells) positions.add(coords(cell));
        double centerX = positions.stream().mapToDouble(c -> c[0] + 0.5).average().orElse(0);
        double centerZ = positions.stream().mapToDouble(c -> c[2] + 0.5).average().orElse(0);
        positions.sort(Comparator.<int[]>comparingInt(c -> c[1])
                .thenComparingDouble(c -> Math.pow(c[0] + 0.5 - centerX, 2)
                        + Math.pow(c[2] + 0.5 - centerZ, 2)));

        for (BlockFace face : sides) {
            for (int drop = 0; drop <= 1; drop++) {
                for (int[] c : positions) {
                    Block portal = w.getBlockAt(c[0], c[1], c[2]);
                    for (int distance = 1; distance <= 2; distance++) {
                        Block feet = portal.getRelative(face, distance).getRelative(BlockFace.DOWN, drop);
                        Block head = feet.getRelative(BlockFace.UP);
                        Block floor = feet.getRelative(BlockFace.DOWN);
                        if (!feet.getChunk().isLoaded() || !head.getChunk().isLoaded()
                                || !floor.getChunk().isLoaded()) continue;
                        if (!isSafe(feet) || !isSafe(head) || !isSafeLandingFloor(floor)) continue;
                        if (contains(feet) || contains(head)) continue;
                        if (distance == 2) {
                            // Ensure the intervening block is not a wall. It may
                            // be the portal's own air interior at a lower elevation.
                            Block approach = portal.getRelative(face, 1).getRelative(BlockFace.DOWN, drop);
                            if (!isSafe(approach) || !isSafe(approach.getRelative(BlockFace.UP))) continue;
                        }
                        Location result = feet.getLocation().add(0.5, 0, 0.5);
                        result.setYaw(exitYaw(face));
                        return result;
                    }
                }
            }
        }
        return null;
    }
    private static boolean isSafeLandingFloor(Block block){
        Material type=block.getType();
        return type.isSolid() && !block.isLiquid() && !block.isEmpty()
                && type!=Material.MAGMA_BLOCK && type!=Material.CACTUS;
    }
    private static boolean isSafe(Block block){
        if(!block.isPassable() || block.isLiquid())return false;
        Material type=block.getType();
        return type!=Material.FIRE && type!=Material.SOUL_FIRE
                && type!=Material.COBWEB && type!=Material.POWDER_SNOW
                && type!=Material.CACTUS && type!=Material.SWEET_BERRY_BUSH
                && type!=Material.WITHER_ROSE && type!=Material.MAGMA_BLOCK;
    }
}
