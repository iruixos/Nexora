package com.iruixos.nexora;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** Experimental resource-pack-backed portal plane. Does not modify world blocks. */
final class EventHorizonRenderer {
    private final JavaPlugin plugin;
    private final Map<UUID, UUID> displays = new HashMap<>();
    private String activeTexture = "";
    private java.util.function.Consumer<Entity> spawnListener = entity -> {};
    void onSpawn(java.util.function.Consumer<Entity> listener) { this.spawnListener = listener; }
    EventHorizonRenderer(JavaPlugin plugin){this.plugin=plugin;}
    void sync(Collection<Gate> gates){
        if(!plugin.getConfig().getString("portal.render-mode","WATER").equalsIgnoreCase("EVENT_HORIZON")){
            clear();return;
        }
        String configured = plugin.getConfig().getString("portal.texture", "CRYSTAL_SPRING");
        String texture = configured == null ? "CRYSTAL_SPRING" : configured.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CLASSIC", "AQUA_FLOW", "DEEP_ABYSS", "CELESTIAL_VORTEX", "CRYSTAL_SPRING", "AZURE_SKY", "PEARL_LAGOON", "BUBBLING_POOL").contains(texture)) {
            if (!texture.equals(activeTexture)) plugin.getLogger().warning("Unknown portal.texture '" + texture + "'; using CRYSTAL_SPRING.");
            texture = "CRYSTAL_SPRING";
        }
        if (!texture.equals(activeTexture)) {
            clear();
            activeTexture = texture;
        }
        final String modelKey = switch (texture) {
            case "AQUA_FLOW" -> "aqua_flow";
            case "DEEP_ABYSS" -> "deep_abyss";
            case "CELESTIAL_VORTEX" -> "celestial_vortex";
            case "CRYSTAL_SPRING" -> "crystal_spring";
            case "AZURE_SKY" -> "azure_sky";
            case "PEARL_LAGOON" -> "pearl_lagoon";
            case "BUBBLING_POOL" -> "bubbling_pool";
            default -> "water_curtain";
        };
        Set<UUID> valid=new HashSet<>();
        for(Gate gate:gates){
            if(gate.cells.isEmpty() || gate.world()==null)continue;
            World world=gate.world();
            boolean loaded=true;
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
            int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(String cell:gate.cells){
                int[] c=Gate.coords(cell);
                if(!world.isChunkLoaded(c[0]>>4,c[2]>>4)){loaded=false;break;}
                minX=Math.min(minX,c[0]);minY=Math.min(minY,c[1]);minZ=Math.min(minZ,c[2]);
                maxX=Math.max(maxX,c[0]);maxY=Math.max(maxY,c[1]);maxZ=Math.max(maxZ,c[2]);
            }
            if(!loaded)continue;
            valid.add(gate.id);
            Entity existing=displays.containsKey(gate.id)?Bukkit.getEntity(displays.get(gate.id)):null;
            if(existing instanceof ItemDisplay && existing.isValid())continue;
            displays.remove(gate.id);
            float width=gate.plane.equals("X")?maxX-minX+1:maxZ-minZ+1;
            float height=maxY-minY+1;
            Location center=new Location(world,(minX+maxX+1)/2.0,(minY+maxY+1)/2.0,(minZ+maxZ+1)/2.0);
            ItemStack item=new ItemStack(Material.PAPER);
            ItemMeta meta=item.getItemMeta();
            meta.setItemModel(new NamespacedKey("nexora",modelKey));
            item.setItemMeta(meta);
            final float fw=width,fh=height;
            final boolean rotate=gate.plane.equals("Z");
            ItemDisplay display=world.spawn(center,ItemDisplay.class,entity->{
                entity.setItemStack(item);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                Quaternionf rotation=rotate?new Quaternionf().rotateY((float)Math.PI/2):new Quaternionf();
                entity.setTransformation(new org.bukkit.util.Transformation(new Vector3f(),rotation,new Vector3f(fw,fh,1),new Quaternionf()));
                entity.setViewRange(8f);
                entity.setDisplayWidth(Math.max(1,fw));entity.setDisplayHeight(Math.max(1,fh));
                entity.setPersistent(false);
                entity.addScoreboardTag("nexora-event-horizon");
            });
            displays.put(gate.id,display.getUniqueId());
            spawnListener.accept(display);
        }
        for(UUID id:new HashSet<>(displays.keySet()))if(!valid.contains(id))remove(id);
    }
    Collection<Entity> entities(){
        List<Entity> result = new ArrayList<>();
        for (UUID id : displays.values()) { Entity e = Bukkit.getEntity(id); if (e != null) result.add(e); }
        return result;
    }
    void remove(UUID id){UUID entityId=displays.remove(id);if(entityId!=null){Entity entity=Bukkit.getEntity(entityId);if(entity!=null)entity.remove();}}
    void clear(){for(UUID id:new HashSet<>(displays.keySet()))remove(id);}
}
