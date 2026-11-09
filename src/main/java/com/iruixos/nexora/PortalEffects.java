package com.iruixos.nexora;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.util.*;
import java.util.function.Supplier;

/** All world, sound and particle calls occur on the primary server thread. */
final class PortalEffects {
    private final NexoraPlugin plugin;
    private final Supplier<List<Gate>> gates;
    private YamlConfiguration config;
    private BukkitTask ambientTask;
    private final Random random=new Random();
    PortalEffects(NexoraPlugin plugin,Supplier<List<Gate>> gates){this.plugin=plugin;this.gates=gates;}
    void start(){File file=new File(plugin.getDataFolder(),"effects.yml");if(!file.exists())plugin.saveResource("effects.yml",false);reload();}
    void stop(){if(ambientTask!=null){ambientTask.cancel();ambientTask=null;}}
    void reload(){
        stop();
        config=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"effects.yml"));
        if(!config.getBoolean("ambient.enabled",true))return;
        long interval=Math.max(5,config.getLong("ambient.interval-ticks",20));
        ambientTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::ambient,interval,interval);
    }
    private Particle particle(String path,Particle fallback){
        String name=config.getString(path,fallback.name());
        try{return Particle.valueOf(name.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException ex){plugin.getLogger().warning("Invalid particle at "+path+": "+name);return fallback;}
    }
    private Sound sound(String path,Sound fallback){
        String name=config.getString(path,fallback.name());
        try{return Sound.valueOf(name.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException ex){plugin.getLogger().warning("Invalid sound at "+path+": "+name);return fallback;}
    }
    private void ambient(){
        Particle type=particle("ambient.particle",Particle.ENCHANT);
        int count=Math.max(0,Math.min(30,config.getInt("ambient.particles-per-cycle",4)));
        double range=Math.max(1,Math.min(128,config.getDouble("ambient.player-range",24)));
        for(Gate gate:gates.get()){
            World world=gate.world();if(world==null||gate.cells.isEmpty())continue;
            // Avoid loading chunks solely to render decorative effects.
            String cell=gate.cells.get(random.nextInt(gate.cells.size()));int[] xyz=Gate.coords(cell);
            if(!world.isChunkLoaded(xyz[0]>>4,xyz[2]>>4))continue;
            Location location=new Location(world,xyz[0]+.5,xyz[1]+.5,xyz[2]+.5);
            if(world.getPlayers().stream().noneMatch(p->p.getLocation().distanceSquared(location)<=range*range))continue;
            world.spawnParticle(type,location,count,.45,.55,.45,.01);
        }
    }
    private void burst(Location at,String prefix,Particle fallback){
        if(at==null||at.getWorld()==null||!config.getBoolean(prefix+".enabled",true))return;
        int count=Math.max(0,Math.min(200,config.getInt(prefix+".count",24)));
        at.getWorld().spawnParticle(particle(prefix+".particle",fallback),at.clone().add(0,1,0),count,.55,.9,.55,.07);
    }
    private void play(Location at,String prefix,Sound fallback){
        if(at==null||at.getWorld()==null||!config.getBoolean(prefix+".enabled",true))return;
        float volume=(float)Math.max(0,Math.min(4,config.getDouble(prefix+".volume",.6)));
        float pitch=(float)Math.max(.5,Math.min(2,config.getDouble(prefix+".pitch",1.2)));
        at.getWorld().playSound(at,sound(prefix+".type",fallback),volume,pitch);
    }
    private Location centre(Gate gate){
        World w=gate.world();if(w==null||gate.cells.isEmpty())return null;
        double x=0,y=0,z=0;
        for(String cell:gate.cells){int[] c=Gate.coords(cell);x+=c[0]+.5;y+=c[1]+.5;z+=c[2]+.5;}
        int n=gate.cells.size();Location at=new Location(w,x/n,y/n,z/n);
        if(!w.isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4))return null;
        return at;
    }
    void activation(Gate gate){Location at=centre(gate);burst(at,"activation.particles",Particle.END_ROD);play(at,"activation.sound",Sound.BLOCK_BEACON_ACTIVATE);}
    void deactivation(Gate gate){Location at=centre(gate);burst(at,"deactivation.particles",Particle.SMOKE);play(at,"deactivation.sound",Sound.BLOCK_BEACON_DEACTIVATE);}
    void teleport(Location from,Location to){
        burst(from,"teleport.departure",Particle.PORTAL);
        burst(to,"teleport.arrival",Particle.END_ROD);
        play(from,"teleport.sound",Sound.ENTITY_ENDERMAN_TELEPORT);
        play(to,"teleport.sound",Sound.ENTITY_ENDERMAN_TELEPORT);
    }
}
