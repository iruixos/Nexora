package com.iruixos.nexora;

import org.bukkit.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.*;

public final class NexoraPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private GateStore store;
    private PortalEffects effects;
    private PortalWaterOverlay waterOverlay;
    private WaterFrameExperiment waterFrame;
    private EventHorizonRenderer horizon;
    private NexoraResourcePack resourcePack;
    private LegacyPortalFallback fallback;
    Collection<Gate> fallbackGates(){return List.copyOf(gates.values());}
    private boolean horizonMode(){return getConfig().getString("portal.render-mode","WATER").equalsIgnoreCase("EVENT_HORIZON");}
    // Per-player, client-only preview. Never modifies the server world.
    private final Map<UUID, Set<Location>> waterPreview = new HashMap<>();
    private final Map<UUID,Gate> gates=new LinkedHashMap<>();
    private final Map<String,UUID> occupied=new HashMap<>();
    private final Map<UUID,Long> cooldowns=new HashMap<>();
    private final Random random=new Random();
    private String blockKey(UUID world,String cell){return world+":"+cell;}
    private void logStartupBanner() {
        String version = getPluginMeta().getVersion();
        String minecraftVersion = Bukkit.getMinecraftVersion();
        String[] lines = {
                "[N] Nexora v" + version,
                "    by iruixos",
                "    Paper " + minecraftVersion + " / MC " + minecraftVersion
        };
        int innerWidth = Math.max(31, Arrays.stream(lines).mapToInt(String::length).max().orElse(0) + 2);
        String border = "+" + "-".repeat(innerWidth) + "+";
        getLogger().info("");
        getLogger().info("  " + border);
        for (String line : lines) {
            getLogger().info("  | " + line + " ".repeat(innerWidth - line.length() - 1) + "|");
        }
        getLogger().info("  " + border);
        getLogger().info("");
    }

    @Override public void onEnable(){
        saveDefaultConfig();
        if (!ConfigMigration.migrate(this)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reloadConfig();
        try {store=new GateStore(getDataFolder().toPath().resolve("gates.db"));for(Gate g:store.load())register(g);}
        catch(Exception e){getLogger().severe("Could not load portal database: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        effects=new PortalEffects(this, () -> List.copyOf(gates.values()));
        effects.start();
        waterOverlay=new PortalWaterOverlay(this);
        waterFrame=new WaterFrameExperiment(this);
        horizon=new EventHorizonRenderer(this);
        fallback=new LegacyPortalFallback(this,horizon);
        horizon.onSpawn(fallback::onDisplaySpawn);
        getServer().getPluginManager().registerEvents(fallback,this);
        resourcePack=new NexoraResourcePack(this);
        resourcePack.start();
        getServer().getPluginManager().registerEvents(this,this);
        // Periodic validation catches explosions, piston movement, external edits and
        // changes made while the gate was unloaded. Never force-load chunks.
        getServer().getScheduler().runTaskTimer(this,this::validateLoadedGates,40L,40L);
        getServer().getScheduler().runTaskTimer(this,()->horizon.sync(gates.values()),30L,40L);
        getServer().getScheduler().runTaskTimer(this,()->waterFrame.tick(gates.values()),20L,10L);
        getServer().getScheduler().runTaskTimer(this,()->fallback.tick(),50L,20L);
        Objects.requireNonNull(getCommand("nexora")).setExecutor(this);
        Objects.requireNonNull(getCommand("nexora")).setTabCompleter(this);
        logStartupBanner();
        getLogger().info("Loaded "+gates.size()+" portals");
    }
    @EventHandler public void onPackJoin(PlayerJoinEvent event) {
        if (resourcePack != null) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (event.getPlayer().isOnline()) resourcePack.send(event.getPlayer());
            }, 40L);
        }
    }
    @EventHandler public void onPreviewQuit(PlayerQuitEvent event){
        waterPreview.remove(event.getPlayer().getUniqueId());
        waterFrame.forget(event.getPlayer());
    }
    private void clearWaterPreview(Player player){
        Set<Location> locations=waterPreview.remove(player.getUniqueId());
        if(locations==null)return;
        waterFrame.resumePreview(player,locations);
        for(Location location:locations){
            if(location.getWorld()!=null && location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4)){
                player.sendBlockChange(location,location.getBlock().getBlockData());
            }
        }
    }
    private void waterPreviewCommand(CommandSender sender,String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("§cPlayer-only preview.");return;}
        if(!sender.hasPermission("nexora.admin")){sender.sendMessage("§cAdministrator permission required.");return;}
        if(args.length!=2 || !List.of("water","waterlogged","reset").contains(args[1].toLowerCase(Locale.ROOT))){
            sender.sendMessage("§eUsage: /nexora waterpreview <water|waterlogged|reset>");return;
        }
        clearWaterPreview(player);
        String mode=args[1].toLowerCase(Locale.ROOT);
        if(mode.equals("reset")){sender.sendMessage("§aPreview reset.");return;}
        Gate gate=targetedGate(player);
        if(gate==null){sender.sendMessage("§eLook toward a registered portal within 12 blocks.");return;}
        World world=gate.world();
        if(world==null)return;
        int top=gate.cells.stream().mapToInt(c->Gate.coords(c)[1]).max().orElse(Integer.MIN_VALUE);
        Set<Location> changed=new HashSet<>();
        int unsupported=0;
        for(String cell:gate.cells){
            int[] xyz=Gate.coords(cell);
            if(xyz[1]!=top || !world.isChunkLoaded(xyz[0]>>4,xyz[2]>>4))continue;
            Block frame=world.getBlockAt(xyz[0],xyz[1]+1,xyz[2]);
            BlockData preview;
            if(mode.equals("water")){
                preview=Bukkit.createBlockData(Material.WATER);
            }else{
                preview=frame.getBlockData().clone();
                if(!(preview instanceof org.bukkit.block.data.Waterlogged waterlogged)){
                    unsupported++;continue;
                }
                waterlogged.setWaterlogged(true);
            }
            player.sendBlockChange(frame.getLocation(),preview);
            changed.add(frame.getLocation());
        }
        if(!changed.isEmpty()){
            waterFrame.suspendPreview(player,changed);
            waterPreview.put(player.getUniqueId(),changed);
            // Restore the actual frame states after 15 seconds, even if no command is run.
            getServer().getScheduler().runTaskLater(this,()->clearWaterPreview(player),300L);
        }
        sender.sendMessage("§eClient-only "+mode+" preview on "+changed.size()+" top-frame blocks; restores in 15 seconds.");
        if(unsupported>0)sender.sendMessage("§7"+unsupported+" frame blocks cannot be waterlogged.");
        if(mode.equals("water"))sender.sendMessage("§7The frame will appear as water temporarily. This is intentional for the rendering test.");
    }
    private void waterFrameDebug(CommandSender sender){
        if(!sender.hasPermission("nexora.admin")){sender.sendMessage("§cAdministrator permission required.");return;}
        sender.sendMessage("§bNexora water-frame diagnostics:");
        sender.sendMessage("§7Enabled: §f"+getConfig().getBoolean("portal.experimental-water-frame.enabled",false)
                +" §7Registered gates: §f"+gates.size());
        for(String line:waterFrame.debugLines())sender.sendMessage("§7"+line);
        if(sender instanceof Player player){
            Gate gate=targetedGate(player);
            sender.sendMessage(gate==null?"§eTarget: no gate in view within 12 blocks."
                    :"§aTarget: "+gate.networkDisplay+" §7("+gate.id+") §7interior="+gate.cells.size());
        }
    }
    @Override public void onDisable(){if(fallback!=null)fallback.reset();for(Player player:Bukkit.getOnlinePlayers())clearWaterPreview(player);if(resourcePack!=null)resourcePack.stop();if(waterFrame!=null)waterFrame.clear();if(horizon!=null)horizon.clear();if(waterOverlay!=null)waterOverlay.clear();if(effects!=null)effects.stop();if(store!=null)try{store.close();}catch(SQLException e){getLogger().warning(e.getMessage());}}
    private void register(Gate g){gates.put(g.id,g);for(String c:g.cells)occupied.put(blockKey(g.worldId,c),g.id);}
    private void unregister(Gate g){gates.remove(g.id);for(String c:g.cells)occupied.remove(blockKey(g.worldId,c));}
    private String normalized(String name){return Normalizer.normalize(name.strip(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private List<Gate> networkGates(String key){return gates.values().stream().filter(g->g.networkKey.equals(key)).toList();}
    private String canonicalRouting(String s){return switch(s.toUpperCase(Locale.ROOT)){case "PAIR","ROUND_ROBIN","RANDOM"->s.toUpperCase(Locale.ROOT);default->"PAIR";};}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void activate(PlayerInteractEvent e){
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND)return;
        Player p=e.getPlayer();ItemStack item=e.getItem();
        if(item==null||item.getType()!=Material.CLOCK||!item.hasItemMeta()||!item.getItemMeta().hasDisplayName())return;
        if(!p.hasPermission("nexora.create"))return;
        Block clicked=e.getClickedBlock();if(clicked==null)return;
        Block start=clicked.getRelative(e.getBlockFace());
        if(!start.getType().isAir()&&start.getType()!=Material.WATER)return;
        String display=org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName()).strip();
        if(display.isEmpty()||display.length()>getConfig().getInt("activation.max-name-length",32)){p.sendMessage("§cInvalid gate name.");return;}
        String configuredBlock=getConfig().getString("activation.activation-block", "EMERALD_BLOCK");
        Material activationBlock=Material.matchMaterial(configuredBlock == null ? "" : configuredBlock);
        if(activationBlock==null || !activationBlock.isBlock() || !activationBlock.isSolid()) {
            p.sendMessage("§cNexora configuration error: invalid activation block. Contact an administrator.");
            getLogger().warning("Invalid activation.activation-block: " + configuredBlock);
            return;
        }
        FrameDetector.Frame frame=FrameDetector.detect(start,getConfig().getInt("portal.max-interior-blocks",225),activationBlock,getConfig().getInt("activation.minimum-activation-blocks",2));
        if(frame==null)return;
        e.setCancelled(true);
        String key=normalized(display);
        for(Block b:frame.interior())if(occupied.containsKey(blockKey(b.getWorld().getUID(),Gate.key(b)))){p.sendMessage("§cThis frame intersects an existing gate.");return;}
        try {
            GateStore.Network net=store.network(key);
            if(net!=null){
                if(getConfig().getBoolean("networks.require-authorization-to-join",true)&&!net.owner().equals(p.getUniqueId())&&!p.hasPermission("nexora.admin")){p.sendMessage("§cYou are not authorized to join this network.");return;}
                if(net.routing().equals("PAIR")&&networkGates(key).size()>=2){p.sendMessage("§cThis paired network already has two gates.");return;}
            }
            Map<String,String> originals=new LinkedHashMap<>();List<String> cells=new ArrayList<>();
            for(Block b:frame.interior()){String c=Gate.key(b);cells.add(c);originals.put(c,b.getBlockData().getAsString());}
            Gate gate=new Gate(UUID.randomUUID(),start.getWorld().getUID(),p.getUniqueId(),key,net==null?display:net.display(),frame.plane(),cells,originals,null);
            // A gate must have an unambiguous creator-facing exit direction.
            // Never infer a side from a player standing inside the portal plane.
            double planeAxis = 0.0;
            for (String cell : gate.cells) {
                int[] xyz = Gate.coords(cell);
                planeAxis += (gate.plane.equals("X") ? xyz[2] : xyz[0]) + 0.5;
            }
            planeAxis /= gate.cells.size();
            double creatorAxis = gate.plane.equals("X") ? p.getLocation().getZ() : p.getLocation().getX();
            if (Math.abs(creatorAxis - planeAxis) < 0.35) {
                p.sendMessage("§eStand clearly on the side of the gate where arriving players should exit, then activate it again.");
                return;
            }
            gate.setPreferredExit(gate.sideFrom(p.getLocation()));
            if(net==null)store.insertNetwork(new GateStore.Network(key,display,p.getUniqueId(),canonicalRouting(getConfig().getString("networks.default-routing","PAIR"))));
            try{store.insert(gate);}catch(SQLException ex){if(net==null)store.deleteEmptyNetwork(key);throw ex;}
            register(gate);
            for(Block b:frame.interior())if(horizonMode())b.setType(Material.AIR,false);else setFullWaterSource(b);
            getServer().getScheduler().runTask(this, () -> {
                if(gates.containsKey(gate.id)){if(!horizonMode()){refreshPortalWater(gate);waterOverlay.sync(gate);waterFrame.refresh(gates.values());}horizon.sync(gates.values());}
            });
            effects.activation(gate);
            p.sendMessage("§aGate created: §f" + gate.networkDisplay + " §7(" + gate.id + ") §aExit: §f" + gate.preferredExit().name());
        }catch(SQLException ex){getLogger().severe("Create gate failed: "+ex.getMessage());p.sendMessage("§cDatabase error. Gate not created.");}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e){
        if(e.getTo()==null||e.getFrom().getBlock().equals(e.getTo().getBlock()))return;
        Player p=e.getPlayer();if(!p.hasPermission("nexora.use"))return;
        UUID id=occupied.get(blockKey(e.getTo().getWorld().getUID(),Gate.key(e.getTo().getBlock())));
        if(id==null || (horizonMode() ? !e.getTo().getBlock().getType().isAir() : e.getTo().getBlock().getType()!=Material.WATER))return;
        long now=System.currentTimeMillis();if(cooldowns.getOrDefault(p.getUniqueId(),0L)>now)return;
        Gate source=gates.get(id);if(source==null)return;
        try{
            GateStore.Network net=store.network(source.networkKey);if(net==null)return;
            // Stable UUID ordering defines a fixed directional cycle. Every source gate
            // goes to the next eligible gate, wrapping to the first; the order survives restarts.
            List<Gate> members=networkGates(source.networkKey).stream()
                    .sorted(java.util.Comparator.comparing(g->g.id.toString())).toList();
            List<Gate> eligible=members.stream().filter(g->!g.id.equals(source.id))
                    .filter(g->getConfig().getBoolean("teleportation.allow-cross-world",true)||g.worldId.equals(source.worldId)).toList();
            if(eligible.isEmpty())return;
            Gate target;
            switch(net.routing()){
                case "RANDOM"->target=eligible.get(random.nextInt(eligible.size()));
                case "ROUND_ROBIN"->{
                    int sourceIndex=members.indexOf(source);
                    Gate next=null;
                    for(int step=1;step<members.size();step++){
                        Gate candidate=members.get((sourceIndex+step)%members.size());
                        if(eligible.contains(candidate)){next=candidate;break;}
                    }
                    if(next==null)return;
                    target=next;
                }
                default->target=eligible.get(0);
            }
            Location arrival=target.arrival();if(arrival==null){p.sendMessage("§cDestination has no safe exit.");return;}
            cooldowns.put(p.getUniqueId(),now+getConfig().getLong("teleportation.cooldown-seconds",3)*1000L);
            arrival.setPitch(p.getLocation().getPitch());
            Location departure=p.getLocation().clone();
            p.teleportAsync(arrival).thenAccept(success->getServer().getScheduler().runTask(this,()->{
                if(!p.isOnline())return;
                if(success){effects.teleport(departure,p.getLocation().clone());}
                else cooldowns.remove(p.getUniqueId());
            }));
        }catch(SQLException ex){getLogger().warning("Teleport lookup failed: "+ex.getMessage());}
    }
    // A gate's interior is protected, but owners may dismantle its frame.
    // After the break is applied, validation removes the invalid gate and water.
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent e){
        Block b=e.getBlock();Gate g=gateNear(b);
        if(g==null)return;
        if(!g.owner.equals(e.getPlayer().getUniqueId())&&!e.getPlayer().hasPermission("nexora.admin")){
            e.setCancelled(true);e.getPlayer().sendMessage("§cThis gate belongs to another player.");return;
        }
        if(g.contains(b)){
            e.setCancelled(true);e.getPlayer().sendMessage("§eUse /nexora remove to remove the portal interior.");return;
        }
        getServer().getScheduler().runTask(this,()->validateGateIfLoaded(g));
    }
    private boolean allGateChunksLoaded(Gate gate){
        World world=gate.world();if(world==null)return false;
        for(String cell:gate.cells){
            int[] xyz=Gate.coords(cell);
            if(!world.isChunkLoaded(xyz[0]>>4,xyz[2]>>4))return false;
        }
        return true;
    }
    // Explicitly set water level to zero (source), including the top row.
    // Physics are suppressed so nearby fluid updates do not cascade during activation.
    private void setFullWaterSource(Block block){
        org.bukkit.block.data.Levelled water=(org.bukkit.block.data.Levelled)
                Bukkit.createBlockData(Material.WATER);
        water.setLevel(0);
        block.setBlockData(water,false);
    }
    private void refreshPortalWater(Gate gate){
        for(String cell:gate.cells){
            Block block=gate.block(cell);
            if(block==null || block.getType()!=Material.WATER)continue;
            if(block.getBlockData() instanceof org.bukkit.block.data.Levelled level && level.getLevel()!=0){
                setFullWaterSource(block);
            }
        }
    }
    private boolean frameIntact(Gate gate){
        World world=gate.world();if(world==null)return true;
        Set<String> interior=new HashSet<>(gate.cells);
        BlockFace[] directions=gate.plane.equals("X")
                ? new BlockFace[]{BlockFace.EAST,BlockFace.WEST,BlockFace.UP,BlockFace.DOWN}
                : new BlockFace[]{BlockFace.NORTH,BlockFace.SOUTH,BlockFace.UP,BlockFace.DOWN};
        for(String cell:gate.cells){
            Block block=gate.block(cell);
            if(block==null || (horizonMode()? !block.getType().isAir() : block.getType()!=Material.WATER))return false;
            for(BlockFace face:directions){
                Block adjacent=block.getRelative(face);
                if(!world.isChunkLoaded(adjacent.getX()>>4,adjacent.getZ()>>4))continue;
                if(!interior.contains(Gate.key(adjacent)) && !adjacent.getType().isSolid())return false;
            }
        }
        return true;
    }
    private void validateLoadedGates(){
        for(Gate gate:new ArrayList<>(gates.values()))validateGateIfLoaded(gate);
        if(!horizonMode())waterOverlay.syncLoaded(gates.values());else waterOverlay.clear();
        if(!horizonMode())waterFrame.refresh(gates.values());else waterFrame.clear();
        horizon.sync(gates.values());
    }
    private void validateGateIfLoaded(Gate gate){
        if(!gates.containsKey(gate.id) || !allGateChunksLoaded(gate))return;
        if(horizonMode() && gate.cells.stream().allMatch(c -> {Block b=gate.block(c);return b!=null && (b.getType()==Material.WATER || b.getType().isAir());})){
            for(String cell:gate.cells){Block b=gate.block(cell);if(b.getType()==Material.WATER)b.setType(Material.AIR,false);}
        }
        if(frameIntact(gate)){
            if(!horizonMode())refreshPortalWater(gate);
            return;
        }
        try{
            // Delete the database record before changing world blocks.
            store.delete(gate.id);
            waterOverlay.remove(gate.id);
            waterFrame.remove(gate.id);
            horizon.remove(gate.id);
            unregister(gate);
            store.deleteEmptyNetwork(gate.networkKey);
            effects.deactivation(gate);
            for(String cell:gate.cells){
                Block block=gate.block(cell);
                if(block!=null && (block.getType()==Material.WATER || (horizonMode() && block.getType().isAir()))){
                    // Clear the old portal interior without spawning floating water.
                    block.setType(Material.AIR,false);
                }
            }
            getLogger().info("Deactivated gate "+gate.id+" (frame damaged or portal interior changed).");
        }catch(SQLException ex){getLogger().severe("Could not deactivate damaged gate: "+ex.getMessage());}
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent e){if(occupied.containsKey(blockKey(e.getBlock().getWorld().getUID(),Gate.key(e.getBlock()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onFlow(BlockFromToEvent e){if(occupied.containsKey(blockKey(e.getBlock().getWorld().getUID(),Gate.key(e.getBlock())))||occupied.containsKey(blockKey(e.getToBlock().getWorld().getUID(),Gate.key(e.getToBlock()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onBucket(org.bukkit.event.player.PlayerBucketEmptyEvent e){if(occupied.containsKey(blockKey(e.getBlock().getWorld().getUID(),Gate.key(e.getBlock()))))e.setCancelled(true);}
    // Select by the actual registered gate geometry, not whichever block a ray first hits.
    // This also handles aiming at the gold activation block or any part of the frame.
    private Gate targetedGate(Player player){
        Location eye=player.getEyeLocation();
        org.bukkit.util.Vector direction=eye.getDirection().normalize();
        Gate best=null;
        double bestAlong=Double.POSITIVE_INFINITY;
        for(Gate gate:gates.values()){
            if(!gate.worldId.equals(player.getWorld().getUID()) || gate.cells.isEmpty())continue;
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
            double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
            for(String cell:gate.cells){
                int[] c=Gate.coords(cell);
                minX=Math.min(minX,c[0]);minY=Math.min(minY,c[1]);minZ=Math.min(minZ,c[2]);
                maxX=Math.max(maxX,c[0]+1);maxY=Math.max(maxY,c[1]+1);maxZ=Math.max(maxZ,c[2]+1);
            }
            // Include the entire surrounding frame, including the top activation block.
            minX-=1;minY-=1;minZ-=1;maxX+=1;maxY+=1;maxZ+=1;
            org.bukkit.util.Vector origin=eye.toVector();
            double tMin=0,tMax=12;
            double[] origins={origin.getX(),origin.getY(),origin.getZ()};
            double[] dirs={direction.getX(),direction.getY(),direction.getZ()};
            double[] mins={minX,minY,minZ},maxs={maxX,maxY,maxZ};
            boolean hit=true;
            for(int axis=0;axis<3;axis++){
                if(Math.abs(dirs[axis])<1.0e-9){
                    if(origins[axis]<mins[axis] || origins[axis]>maxs[axis]){hit=false;break;}
                    continue;
                }
                double t1=(mins[axis]-origins[axis])/dirs[axis];
                double t2=(maxs[axis]-origins[axis])/dirs[axis];
                tMin=Math.max(tMin,Math.min(t1,t2));
                tMax=Math.min(tMax,Math.max(t1,t2));
                if(tMax<tMin){hit=false;break;}
            }
            if(hit && tMin<bestAlong){best=gate;bestAlong=tMin;}
        }
        return best;
    }
    private Gate resolveGate(String identifier){
        try {return gates.get(UUID.fromString(identifier));}catch(IllegalArgumentException ignored){}
        List<Gate> matches=gates.values().stream().filter(g->g.id.toString().startsWith(identifier.toLowerCase(Locale.ROOT))).toList();
        if(matches.size()!=1)throw new IllegalArgumentException(matches.isEmpty()?"No gate matches that ID.":"Ambiguous short ID; use more characters.");
        return matches.get(0);
    }
    private Gate gateNear(Block b){
        UUID id=occupied.get(blockKey(b.getWorld().getUID(),Gate.key(b)));if(id!=null)return gates.get(id);
        for(BlockFace f:new BlockFace[]{BlockFace.UP,BlockFace.DOWN,BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST}){
            Block n=b.getRelative(f);id=occupied.get(blockKey(n.getWorld().getUID(),Gate.key(n)));if(id!=null)return gates.get(id);
        }
        return null;
    }
    private GateStore.Network selectedNetwork(CommandSender sender,String[] args,int explicitNameStart) throws SQLException {
        if(args.length>explicitNameStart){
            String key=normalized(String.join(" ",Arrays.copyOfRange(args,explicitNameStart,args.length)));
            return store.network(key);
        }
        if(!(sender instanceof Player player)){
            sender.sendMessage("§cConsole usage requires a network name.");
            return null;
        }
        Gate gate=targetedGate(player);
        if(gate==null){sender.sendMessage("§eLook at a gate within 8 blocks or specify a network name.");return null;}
        return store.network(gate.networkKey);
    }
    private boolean mayManageNetwork(CommandSender sender,GateStore.Network net){
        return sender.hasPermission("nexora.admin") ||
                (sender instanceof Player player && sender.hasPermission("nexora.network.manage")
                        && net.owner().equals(player.getUniqueId()));
    }
    private void networkCommand(CommandSender sender,String[] args) throws SQLException {
        if(args.length<2){sender.sendMessage("§eUsage: /nexora network <info|list|routing>");return;}
        switch(args[1].toLowerCase(Locale.ROOT)){
            case "list"->{
                if(!sender.hasPermission("nexora.network.info")&&!sender.hasPermission("nexora.admin")){
                    sender.sendMessage("§cNo permission.");return;
                }
                Set<String> keys=new TreeSet<>();
                for(Gate gate:gates.values()){
                    GateStore.Network net=store.network(gate.networkKey);
                    if(net!=null && (sender.hasPermission("nexora.admin") ||
                            (sender instanceof Player p && net.owner().equals(p.getUniqueId()))))keys.add(net.key());
                }
                sender.sendMessage("§bAccessible networks: §f"+keys.size());
                for(String key:keys){GateStore.Network net=store.network(key);
                    sender.sendMessage("§7- §f"+net.display()+" §7["+net.routing()+", "+networkGates(key).size()+" gates]");}
            }
            case "info"->{
                if(!sender.hasPermission("nexora.network.info")&&!sender.hasPermission("nexora.admin")){
                    sender.sendMessage("§cNo permission.");return;
                }
                GateStore.Network net=selectedNetwork(sender,args,2);
                if(net==null){sender.sendMessage("§cNetwork not found.");return;}
                sender.sendMessage("§bNexora network: §f"+net.display());
                sender.sendMessage("§7Routing: §f"+net.routing()+" §7| Gates: §f"+networkGates(net.key()).size());
                sender.sendMessage("§7Owner UUID: §f"+net.owner());
            }
            case "routing"->{
                if(args.length<3){sender.sendMessage("§eUsage: /nexora network routing <pair|round_robin|random> [network name]");return;}
                String mode=args[2].toUpperCase(Locale.ROOT);
                if(!List.of("PAIR","ROUND_ROBIN","RANDOM").contains(mode)){
                    sender.sendMessage("§cUnknown routing mode. Use pair, round_robin, or random.");return;
                }
                GateStore.Network net=selectedNetwork(sender,args,3);
                if(net==null){sender.sendMessage("§cNetwork not found.");return;}
                if(!mayManageNetwork(sender,net)){sender.sendMessage("§cOnly the network owner or an administrator can change routing.");return;}
                if(mode.equals("PAIR")&&networkGates(net.key()).size()>2){
                    sender.sendMessage("§cCannot enable PAIR: this network has more than two gates. Remove extra gates first.");return;
                }
                store.updateRouting(net.key(),mode);
                sender.sendMessage("§aNetwork §f"+net.display()+" §arouting set to §f"+mode+"§a.");
            }
            default->sender.sendMessage("§cUsage: /nexora network <info|list|routing>");
        }
    }
    private void exitCommand(CommandSender sender,String[] args) throws SQLException {
        if(args.length!=2 || !List.of("set","info","reset").contains(args[1].toLowerCase(Locale.ROOT))){
            sender.sendMessage("§eUsage: /nexora exit <set|info|reset>");return;
        }
        if(!(sender instanceof Player player)){
            sender.sendMessage("§cThis command requires a player looking at a gate.");return;
        }
        Gate gate=targetedGate(player);
        if(gate==null){sender.sendMessage("§eLook at a gate within 8 blocks.");return;}
        String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("info")){
            if(!sender.hasPermission("nexora.network.info")&&!sender.hasPermission("nexora.admin")){
                sender.sendMessage("§cNo permission.");return;
            }
            sender.sendMessage("§bGate exit: §f"+(gate.preferredExit()==null?"AUTO (legacy)":gate.preferredExit().name()));
            return;
        }
        if(!sender.hasPermission("nexora.admin") &&
                (!gate.owner.equals(player.getUniqueId()) || !sender.hasPermission("nexora.exit.manage"))){
            sender.sendMessage("§cOnly the gate owner or an administrator can configure its exit.");return;
        }
        org.bukkit.block.BlockFace face=action.equals("reset")?null:gate.sideFrom(player.getLocation());
        // Determine which side the player is on, using their position, not viewing yaw.
        // Require the player to be clearly on one side of the portal plane.
        if(face!=null){
            double axis=0;
            for(String cell:gate.cells){
                int[] c=Gate.coords(cell);
                axis+=(gate.plane.equals("X")?c[2]:c[0])+0.5;
            }
            axis/=gate.cells.size();
            double playerAxis=gate.plane.equals("X")?player.getLocation().getZ():player.getLocation().getX();
            if(Math.abs(playerAxis-axis)<0.35){
                sender.sendMessage("§cStand clearly on the desired exit side of the gate.");return;
            }
        }
        store.updateExit(gate.id,face);
        gate.setPreferredExit(face);
        sender.sendMessage(face==null?"§aGate exit reset to automatic selection.":
                "§aPreferred exit set to §f"+face.name()+"§a. Arrivals will face away from the water.");
    }
    private void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage("§b" + command + " §7- §f" + description);
    }
    private void showHelp(CommandSender sender, int page) {
        boolean admin = sender.hasPermission("nexora.admin");
        boolean reload = sender.hasPermission("nexora.reload");
        if (page < 1 || page > 3) {
            sender.sendMessage("§cHelp page must be 1, 2, or 3. Example: §f/nexora help 2");
            return;
        }
        sender.sendMessage("§8§m----------------§r §3§lNEXORA §bHelp §7(" + page + "/3) §8§m----------------");
        switch (page) {
            case 1 -> {
                sender.sendMessage("§eGetting Started");
                sender.sendMessage("§7Create a water-filled frame with the configured activation blocks.");
                sender.sendMessage("§7Right-click its interior with a §fnamed clock§7 to activate it.");
                sender.sendMessage("§7Use the same clock name for gates in the same network.");
                if (sender.hasPermission("nexora.use"))
                    sender.sendMessage("§bTravel §7- §fWalk into a gate to teleport to another gate.");
                helpLine(sender, "/nexora help [page]", "Show this guide (pages 1-3).");
                helpLine(sender, "/nexora info", "Look at a gate to view its ID and owner.");
                helpLine(sender, "/nexora exit info", "Show the gate's preferred exit direction.");
                if(sender.hasPermission("nexora.exit.manage") || admin){
                    helpLine(sender, "/nexora exit set", "Stand on the desired arrival side and look at the gate.");
                    helpLine(sender, "/nexora exit reset", "Restore legacy automatic exit selection.");
                }
                if (sender.hasPermission("nexora.destroy.own") || admin)
                    helpLine(sender, "/nexora remove", "Look at your gate to remove it safely.");
                sender.sendMessage("§7Example clock name: §fvette test");
            }
            case 2 -> {
                sender.sendMessage("§eNetwork Management");
                sender.sendMessage("§7Look at a gate within 8 blocks, or supply its network name.");
                if (sender.hasPermission("nexora.network.info") || admin) {
                    helpLine(sender, "/nexora network info [name]", "Show routing, gate count, and owner.");
                    helpLine(sender, "/nexora network list", "List networks you own.");
                }
                if (sender.hasPermission("nexora.network.manage") || admin) {
                    helpLine(sender, "/nexora network routing <mode> [name]", "Change routing for a network you own.");
                    sender.sendMessage("§7Modes: §fPAIR §7(2 gates), §fROUND_ROBIN §7(fixed cycle), §fRANDOM §7(random destination)");
                    sender.sendMessage("§7Example: §f/nexora network routing round_robin");
                    sender.sendMessage("§7By name: §f/nexora network routing random vette test");
                    sender.sendMessage("§7Tip: §fPAIR §7requires removing extra gates first.");
                }
            }
            case 3 -> {
                sender.sendMessage("§eAdministration & Permissions");
                if (admin) {
                    helpLine(sender, "/nexora list", "List every registered gate and its ID.");
                    helpLine(sender, "/nexora remove <id>", "Remove any gate by full or short UUID.");
                    helpLine(sender, "/nexora routing <name> <mode>", "Legacy admin routing command (single-word name).");
                    sender.sendMessage("§7Example: §f/nexora remove a1b2c3d4");
                }
                if (reload)
                    helpLine(sender, "/nexora reload", "Reload config.yml and effects.yml.");
                sender.sendMessage("§7Permissions: §fnexora.use, nexora.create, nexora.destroy.own");
                sender.sendMessage("§7Network: §fnexora.network.info, nexora.network.manage");
                if (admin || reload)
                    sender.sendMessage("§7Staff: §fnexora.admin, nexora.reload");
                sender.sendMessage("§7Only owners or admins can change a network's routing.");
            }
            default -> { }
        }
        sender.sendMessage("§8§m-----------------------------------------");
        sendHelpNavigation(sender, page);
    }
    private Component helpNavigationLink(String label, int page, boolean current) {
        Component text = Component.text(label, current ? NamedTextColor.YELLOW : NamedTextColor.AQUA);
        if (current) return text.decorate(TextDecoration.BOLD);
        return text.clickEvent(ClickEvent.runCommand("/nexora help " + page))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open help page " + page, NamedTextColor.GRAY)));
    }
    private void sendHelpNavigation(CommandSender sender, int page) {
        Component navigation = Component.text("Pages: ", NamedTextColor.GRAY);
        if (page > 1) navigation = navigation.append(helpNavigationLink("◀ Previous", page - 1, false))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY));
        for (int number = 1; number <= 3; number++) {
            if (number > 1) navigation = navigation.append(Component.text(" · ", NamedTextColor.DARK_GRAY));
            navigation = navigation.append(helpNavigationLink(String.valueOf(number), number, number == page));
        }
        if (page < 3) navigation = navigation.append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(helpNavigationLink("Next ▶", page + 1, false));
        sender.sendMessage(navigation);
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(args.length==0||args[0].equalsIgnoreCase("help")){int page=1;if(args.length>1){try{page=Integer.parseInt(args[1]);}catch(NumberFormatException ex){sender.sendMessage("§cUsage: /nexora help [1|2|3]");return true;}}showHelp(sender,page);return true;}
        try{
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "network"->networkCommand(sender,args);
                case "exit"->exitCommand(sender,args);
                case "waterpreview"->waterPreviewCommand(sender,args);
                case "waterframe"->{if(args.length==2 && args[1].equalsIgnoreCase("debug"))waterFrameDebug(sender);else sender.sendMessage("§eUsage: /nexora waterframe debug");}
                case "list"->{if(!sender.hasPermission("nexora.admin")){sender.sendMessage("§cNo permission.");return true;}sender.sendMessage("§bRegistered gates: "+gates.size());gates.values().forEach(g->sender.sendMessage("§7"+g.id.toString().substring(0,8)+" §f"+g.networkDisplay+" §7"+g.worldId));}
                case "info"->{if(!(sender instanceof Player p))return true;Gate g=targetedGate(p);sender.sendMessage(g==null?"§eNo gate targeted.":"§b"+g.networkDisplay+" §7ID: "+g.id+" Owner: "+g.owner);}
                case "remove"->{
                    Gate g;
                    if(args.length>=2){
                        if(!sender.hasPermission("nexora.admin")){sender.sendMessage("§cOnly administrators can remove gates by ID.");return true;}
                        try{g=resolveGate(args[1]);}catch(IllegalArgumentException ex){sender.sendMessage("§c"+ex.getMessage());return true;}
                    }else if(sender instanceof Player p){
                        g=targetedGate(p);
                        if(g==null){sender.sendMessage("§eLook directly at the portal water or frame within 8 blocks, then use /nexora remove.");return true;}
                    }else{sender.sendMessage("§cConsole usage: /nexora remove <short-id|uuid>");return true;}
                    if(sender instanceof Player p && !g.owner.equals(p.getUniqueId())&&!p.hasPermission("nexora.admin")){sender.sendMessage("§cNo permission.");return true;}
                    if(sender instanceof Player p && g.owner.equals(p.getUniqueId())&&!p.hasPermission("nexora.destroy.own")&&!p.hasPermission("nexora.admin")){sender.sendMessage("§cNo permission to remove your gate.");return true;}
                    effects.deactivation(g);
                    store.delete(g.id);waterOverlay.remove(g.id);waterFrame.remove(g.id);horizon.remove(g.id);unregister(g);store.deleteEmptyNetwork(g.networkKey);
                    if(getConfig().getBoolean("portal.restore-interior-on-removal",true))for(var entry:g.originals.entrySet()){Block b=g.block(entry.getKey());if(b!=null&&(b.getType()==Material.WATER || (horizonMode() && b.getType().isAir())))try{b.setBlockData(Bukkit.createBlockData(entry.getValue()),false);}catch(IllegalArgumentException ignored){}}
                    sender.sendMessage("§aGate removed.");}
                case "routing"->{if(args.length<3||!sender.hasPermission("nexora.admin")){sender.sendMessage("§cUsage: /nexora routing <network> <PAIR|ROUND_ROBIN|RANDOM> (admin)");return true;}String mode=args[2].toUpperCase(Locale.ROOT);if(!List.of("PAIR","ROUND_ROBIN","RANDOM").contains(mode)){sender.sendMessage("§cInvalid mode.");return true;}String key=normalized(args[1]);if(store.network(key)==null){sender.sendMessage("§cNetwork not found.");return true;}if(mode.equals("PAIR")&&networkGates(key).size()>2){sender.sendMessage("§cRemove extra gates before enabling PAIR.");return true;}store.updateRouting(key,mode);sender.sendMessage("§aRouting updated to "+mode);}
                case "reload"->{if(!sender.hasPermission("nexora.reload")){sender.sendMessage("§cNo permission.");return true;}reloadConfig();effects.reload();waterOverlay.clear();waterOverlay.syncLoaded(gates.values());waterFrame.clear();waterFrame.refresh(gates.values());horizon.clear();horizon.sync(gates.values());fallback.tick();sender.sendMessage("§aConfiguration and effects reloaded.");}
                default->sender.sendMessage("§cUnknown subcommand. /nexora help");
            }
        }catch(IllegalArgumentException ex){sender.sendMessage("§cInvalid argument: "+ex.getMessage());}
        catch(SQLException ex){getLogger().severe(ex.getMessage());sender.sendMessage("§cDatabase error.");}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return List.of("help","network","exit","waterpreview","waterframe","list","info","remove","routing","reload").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("waterframe")&&sender.hasPermission("nexora.admin"))return List.of("debug").stream().filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("waterpreview")&&sender.hasPermission("nexora.admin"))return List.of("water","waterlogged","reset").stream().filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("exit"))return List.of("set","info","reset").stream().filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("help"))return List.of("1","2","3").stream().filter(v->v.startsWith(args[1])).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("network"))return List.of("info","list","routing").stream().filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==3&&args[0].equalsIgnoreCase("network")&&args[1].equalsIgnoreCase("routing"))return List.of("pair","round_robin","random").stream().filter(v->v.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("remove")&&sender.hasPermission("nexora.admin"))return gates.values().stream().map(g->g.id.toString().substring(0,8)).filter(id->id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==3&&args[0].equalsIgnoreCase("routing"))return List.of("PAIR","ROUND_ROBIN","RANDOM");
        return List.of();
    }
}
