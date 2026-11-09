package com.iruixos.nexora;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import java.util.*;

final class Gate {
    final UUID id, worldId, owner;
    final String networkKey, networkDisplay;
    final String plane;
    final List<String> cells;
    final Map<String,String> originals;
    Gate(UUID id, UUID worldId, UUID owner, String networkKey, String networkDisplay, String plane, List<String> cells, Map<String,String> originals) {
        this.id=id; this.worldId=worldId; this.owner=owner; this.networkKey=networkKey; this.networkDisplay=networkDisplay;
        this.plane=plane; this.cells=List.copyOf(cells); this.originals=Map.copyOf(originals);
    }
    static String key(Block b) {return b.getX()+","+b.getY()+","+b.getZ();}
    static int[] coords(String s) {String[] p=s.split(",");return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};}
    World world(){return Bukkit.getWorld(worldId);}
    Block block(String s){World w=world();if(w==null)return null;int[] c=coords(s);return w.getBlockAt(c[0],c[1],c[2]);}
    boolean contains(Block b){return b.getWorld().getUID().equals(worldId)&&cells.contains(key(b));}
    Location arrival(){
        World w=world();
        if(w==null || cells.isEmpty())return null;
        // Examine the entire portal rather than the arbitrary middle entry in flood-fill order.
        // Prefer positions nearest the horizontal centre, with lower positions first on ties.
        List<int[]> positions=new ArrayList<>();
        for(String cell:cells)positions.add(coords(cell));
        double cx=positions.stream().mapToInt(c->c[0]).average().orElse(0);
        double cy=positions.stream().mapToInt(c->c[1]).average().orElse(0);
        double cz=positions.stream().mapToInt(c->c[2]).average().orElse(0);
        positions.sort(Comparator.<int[]>comparingDouble(c ->
                Math.pow(c[0]-cx,2)+Math.pow(c[2]-cz,2)+0.1*Math.pow(c[1]-cy,2))
                .thenComparingInt(c->c[1]));
        BlockFace side=plane.equals("X")?BlockFace.SOUTH:BlockFace.EAST;
        for(int[] c:positions){
            Block portal=w.getBlockAt(c[0],c[1],c[2]);
            for(BlockFace face:new BlockFace[]{side,side.getOppositeFace()}){
                // Check a short approach corridor; the first block outside the portal may
                // be obstructed by the frame or have no supporting floor.
                for(int distance=1;distance<=2;distance++){
                    Block feet=portal.getRelative(face,distance);
                    Block head=feet.getRelative(BlockFace.UP);
                    Block floor=feet.getRelative(BlockFace.DOWN);
                    if(!feet.getChunk().isLoaded())continue;
                    if(feet.isPassable() && !feet.isLiquid()
                            && head.isPassable() && !head.isLiquid()
                            && floor.getType().isSolid()){
                        return feet.getLocation().add(0.5,0,0.5);
                    }
                }
            }
        }
        return null;
    }
}
