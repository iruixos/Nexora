package com.iruixos.nexora;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import java.util.*;

/** Bounded flood fill in either vertical plane. Never loads arbitrary chunks. */
final class FrameDetector {
    record Frame(String plane, List<Block> interior) {}
    static Frame detect(Block clicked, int maxCells, Material activation, int minimum) {
        for(String plane:List.of("X","Z")) {
            Frame f=scan(clicked,plane,maxCells,activation,minimum);
            if(f!=null)return f;
        }
        return null;
    }
    private static Frame scan(Block start,String plane,int limit,Material activation,int minimum) {
        if(!replaceable(start))return null;
        BlockFace[] directions=plane.equals("X")?new BlockFace[]{BlockFace.EAST,BlockFace.WEST,BlockFace.UP,BlockFace.DOWN}:new BlockFace[]{BlockFace.NORTH,BlockFace.SOUTH,BlockFace.UP,BlockFace.DOWN};
        Set<String> visited=new HashSet<>(), boundary=new HashSet<>();
        ArrayDeque<Block> pending=new ArrayDeque<>(); List<Block> inside=new ArrayList<>();
        pending.add(start);
        int activationCount=0;
        while(!pending.isEmpty()) {
            Block b=pending.removeFirst();String key=Gate.key(b);
            if(!visited.add(key))continue;
            if(!b.getChunk().isLoaded() || !replaceable(b))return null;
            inside.add(b);if(inside.size()>limit)return null;
            for(BlockFace face:directions) {
                Block n=b.getRelative(face);String nk=Gate.key(n);
                if(replaceable(n)) {if(!visited.contains(nk))pending.add(n);}
                else {
                    if(!n.getType().isSolid())return null;
                    if(boundary.add(nk)&&n.getType()==activation)activationCount++;
                }
            }
        }
        if(inside.size()<2||activationCount<minimum)return null;
        return new Frame(plane,inside);
    }
    private static boolean replaceable(Block b){return b.getType()==Material.AIR||b.getType()==Material.CAVE_AIR||b.getType()==Material.WATER;}
}
