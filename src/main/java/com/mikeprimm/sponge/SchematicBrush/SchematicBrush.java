package com.mikeprimm.sponge.SchematicBrush;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;

import com.google.common.reflect.TypeToken;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.command.tool.BrushTool;
import com.sk89q.worldedit.command.tool.InvalidToolBindException;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.session.PasteBuilder;
import com.sk89q.worldedit.sponge.SpongeWorldEdit;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.io.Closer;
import com.sk89q.worldedit.util.io.file.FilenameException;
import com.sk89q.worldedit.world.registry.WorldData;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

@Plugin(id = "schematicbrush")
public class SchematicBrush {
	@Inject private Logger logger;
    @Inject private PluginContainer plugin;
    @Inject @ConfigDir(sharedRoot = false) private File configDir;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private File configuration;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private ConfigurationNode configNode;
    
    public SpongeWorldEdit wep;
    public WorldEdit we;
    
    public static final int DEFAULT_WEIGHT = -1;

    public static enum Flip {
        NONE, NS, EW, RANDOM;
    }
    public static enum Rotation {
        ROT0(0), ROT90(90), ROT180(180), ROT270(270), RANDOM(-1);
        
        final int deg;
        Rotation(int deg) {
            this.deg = deg;
        }
    }
    public static enum Placement {
        CENTER, BOTTOM, DROP
    }
    
    private static Random rnd = new Random();

    public static class SchematicDef {
        public String name;
        public String format;
        public Rotation rotation;
        public Flip flip;
        public int weight;      // If -1, equal weight with other -1 schematics
        public int offset;
        @Override
        public boolean equals(Object o) {
            if (o instanceof SchematicDef) {
                SchematicDef sd = (SchematicDef) o;
                return sd.name.equals(this.name) && (sd.rotation == this.rotation) && (sd.flip == this.flip) && (sd.weight == this.weight) && (sd.offset == this.offset);
            }
            return false;
         }
        @Override
        public String toString() {
            String n = this.name;
            if (format != null)
                n += "#" + format;
            if ((rotation != Rotation.ROT0) || (flip != Flip.NONE)) {
                n += "@";
                if (rotation == Rotation.RANDOM)
                    n += '*';
                else
                    n += (90 * rotation.ordinal());
            }
            if (flip == Flip.RANDOM) {
                n += '*';
            }
            else if (flip == Flip.NS) {
                n += 'N';
            }
            else if (flip == Flip.EW) {
                n += 'E';
            }
            if (weight >= 0) {
                n += ":" + weight;
            }
            if (offset != 0) {
                n += "^" + offset;
            }
            return n;
        }
        public Rotation getRotation() {
            if (rotation == Rotation.RANDOM) {
                return Rotation.values()[rnd.nextInt(4)];
            }
            return rotation;
        }
        public Flip getFlip() {
            if (flip == Flip.RANDOM) {
                return Flip.values()[rnd.nextInt(3)];
            }
            return flip;
        }
        public int getOffset() {
            return offset;
        }
    }
    
    public static class SchematicSet {
        public String name;
        public String desc;
        public List<SchematicDef> schematics;
        public SchematicSet(String n, String d, List<SchematicDef> sch) {
            this.name = n;
            this.desc = (d == null)?"":d;
            this.schematics = (sch == null)?(new ArrayList<SchematicDef>()):sch;
        }
        public int getTotalWeights() {
            int wt = 0;
            for (SchematicDef sd : schematics) {
                if (sd.weight > 0)
                    wt += sd.weight;
            }
            return wt;
        }
        public int getEqualWeightCount() {
            int cnt = 0;
            for (SchematicDef sd : schematics) {
                if (sd.weight <= 0) {
                    cnt++;
                }
            }
            return cnt;
        }
        public SchematicDef getRandomSchematic() {
            int total = getTotalWeights();
            int cnt = getEqualWeightCount();
            int rndval = 0;
            // If any fixed weights
            if (total > 0) {
                // If total fixed more than 100, or equal weight count is zero
                if ((total > 100) || (cnt == 0)) {
                    rndval = rnd.nextInt(total);    // Random from 0 to total-1
                }
                else {
                    rndval = rnd.nextInt(100);      // From 0 to 100 
                }
                if (rndval < total) {   // Fixed weight match
                    for (SchematicDef def : schematics) {
                        if (def.weight > 0) {
                            rndval -= def.weight;
                            if (rndval < 0) {   // Match?
                                return def;
                            }
                        }
                    }
                }
            }
            if (cnt > 0) {
                rndval = rnd.nextInt(cnt);  // Pick from equal weight values
                for (SchematicDef def : schematics) {
                    if (def.weight < 0) {
                        rndval--;
                        if (rndval < 0) {
                            return def;
                        }
                    }
                }
            }
            return null;
        }
    }
    private HashMap<String, SchematicSet> sets = new HashMap<String, SchematicSet>();
    
    public class SchematicBrushInstance implements Brush {
        SchematicSet set;
        Player player;
        boolean skipair;
        boolean replaceall;
        int yoff;
        Placement place;
        
        @Override
        public void build(EditSession editsession, Vector pos, com.sk89q.worldedit.function.pattern.Pattern mat, double size) throws MaxChangedBlocksException {
            SchematicDef def = set.getRandomSchematic();    // Pick schematic from set
            if (def == null) return;
            LocalSession sess = we.getSessionManager().get(player);
            int[] minY = new int[1];
            String schfilename = loadSchematicIntoClipboard(player, sess, def.name, def.format, minY);
            if (schfilename == null) {
                return;
            }
            ClipboardHolder cliph = null;
            Clipboard clip = null;
            try {
                cliph = sess.getClipboard();
            } catch (EmptyClipboardException e) {
                player.printError("Schematic is empty");
                return;
            }
            AffineTransform trans = new AffineTransform();
            // Get rotation for clipboard
            Rotation rot = def.getRotation();
            if (rot != Rotation.ROT0) {
                trans = rotate2D(trans, rot);
            }
            // Get flip option
            Flip flip = def.getFlip();
            switch (flip) {
                case NS:
                    trans = flip(trans, Direction.NORTH);
                    break;
                case EW:
                    trans = flip(trans, Direction.WEST);
                    break;
                default:
                    break;
            }
            cliph.setTransform(trans);

            clip = cliph.getClipboard();
            Region region = clip.getRegion();
            Vector clipOrigin = clip.getOrigin();
            Vector centerOffset = region.getCenter().subtract(clipOrigin);

            // And apply clipboard to edit session
            Vector ppos;
            if (place == Placement.DROP) {
                ppos = new Vector(centerOffset.getX(), -def.offset - yoff - minY[0] + 1, centerOffset.getZ());
            }
            else if (place == Placement.BOTTOM) {
                ppos = new Vector(centerOffset.getX(), -def.offset -yoff + 1, centerOffset.getZ());
            }
            else { // Else, default is CENTER (same as clipboard brush
                ppos = new Vector(centerOffset.getX(), centerOffset.getY() - def.offset - yoff + 1, centerOffset.getZ());
            }
            ppos = trans.apply(ppos);
            ppos = pos.subtract(ppos);

            if (!replaceall) {
                editsession.setMask(new BlockMask(editsession, new BaseBlock(BlockID.AIR, -1)));
            }
            PasteBuilder pb = cliph.createPaste(editsession, editsession.getWorld().getWorldData()).to(ppos)
                    .ignoreAirBlocks(skipair);
            Operations.completeLegacy(pb.build());
            player.print("Applied '" + schfilename + "', flip=" + flip.name() + ", rot=" + rot.deg + ", place=" + place.name());
        }
    }
    
    private AffineTransform rotate2D(AffineTransform trans, Rotation rot) {
        return trans.rotateY(rot.ordinal() * 90);
    }
    
    private AffineTransform flip(AffineTransform trans, Direction dir) {
        return trans.scale(dir.toVector().positive().multiply(-2).add(1,1,1));
    }

    private AffineTransform doOffset(AffineTransform trans, Vector off) {
        return trans.translate(off.multiply(-1));
    }

    public SchematicBrush() {
    }
    
    @Listener
    public void onGamePreInitialization(GamePreInitializationEvent e){
        logger.info("SchematicBrush v" + plugin.getVersion() + " loaded");

        // Intialize config file, if needed
    	try {
    		if (configuration.exists() == false) {
				configuration.createNewFile();
				
				File yamlfile = new File(configDir, "config.yml");
				if (yamlfile.exists()) {
					YAMLConfigurationLoader ycl = YAMLConfigurationLoader.builder().setFile(yamlfile).build();
					configNode = ycl.load();
				}
				else {
					configNode = configManager.load();
					configNode.getNode("schematic-sets").setValue(configNode.getAppendedNode());
				}
				configManager.save(configNode);
    		}
    		configNode = configManager.load();
		} catch (IOException e1) {
			logger.error("Error loading configuration");
		}
        
        PluginContainer wedit = Sponge.getPluginManager().getPlugin("worldedit").orElse(null);
        if (wedit == null) {
            logger.info("WorldEdit not found!");
            return;
        }
        wep = (SpongeWorldEdit) wedit.getInstance().orElse(null);
        if (wep != null) {
        	we = WorldEdit.getInstance();
        }

        // Register commands
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Set schematic brush."))
                .permission(plugin.getId() + ".brush.use")
                .executor(new CommandSCHBR())
                .arguments(GenericArguments.allOf(GenericArguments.string(Text.of("args"))))
                .build(), Arrays.asList("schbr"));
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Set schematic"))
                .permission(plugin.getId() + ".schset.base")
                .arguments(GenericArguments.allOf(GenericArguments.string(Text.of("args"))))
                .executor(new CommandSCHSET())
                .build(), Arrays.asList("schset"));
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("List schematic brushes."))
                .permission(plugin.getId() + ".schlist.base")
                .arguments(GenericArguments.allOf(GenericArguments.string(Text.of("args"))))
                .executor(new CommandSCHLIST())
                .build(), Arrays.asList("schlist"));

    }
    
    @Listener
    public void onGameStartedServer(GameStartedServerEvent e){
        // Initialize bo2 directory, if needed
        File bo2dir = this.getDirectoryForFormat("bo2");
        bo2dir.mkdirs();
        
        // Load existing schematics
        loadSchematicSets();
        // Disable cache
        treecache = null;
    }
    
    public class CommandSCHBR implements CommandExecutor{
    	public CommandSCHBR(){
    	}
    	@Override
    	public CommandResult execute(CommandSource sender, CommandContext ctx) throws CommandException {
    		Actor actor = wep.wrapCommandSource(sender);
    		if (!(actor instanceof Player)) {
    			sender.sendMessage(Text.of("This can only be used by players"));
    			return CommandResult.empty();
    		}
    		Player player = (Player) actor;
    		// Test for command access
    		if (!player.hasPermission("schematicbrush.brush.use")) {
    			sender.sendMessage(Text.of("You do not have access to this command"));
    			return CommandResult.empty();
    		}
    		Collection<String> argstr = ctx.<String>getAll("args");
    		String[] args = argstr.toArray(new String[0]);
    		if (args.length < 1) {
    			player.print("Schematic brush requires &set-id or one or more schematic patterns");
    			return CommandResult.empty();
    		}
    		String setid = null;
    		SchematicSet ss = null;
    		if (args[0].startsWith("&")) {  // If set ID
    			if (player.hasPermission("schematicbrush.set.use") == false) {
    				player.printError("Not permitted to use schematic sets");
    				return CommandResult.empty();
    			}
    			setid = args[0].substring(1);
    			ss = sets.get(setid);
    			if (ss == null) {
    				player.print("Schematic set '" + setid + "' not found");
    				return CommandResult.empty();
    			}
    		}
    		else {
    			ArrayList<SchematicDef> defs = new ArrayList<SchematicDef>();
    			for (int i = 0; i < args.length; i++) {
    				if (args[i].startsWith("-")) { // Option
    				}
    				else {
    					SchematicDef sd = parseSchematic(player, args[i]);
    					if (sd == null) {
    						player.print("Invalid schematic definition: " + args[i]);
    						return CommandResult.empty();
    					}
    					defs.add(sd);
    				}
    			}
    			ss = new SchematicSet(null, null, defs);
    		}
    		boolean skipair = true;
    		boolean replaceall = false;
    		int yoff = 0;
    		Placement place = Placement.CENTER;
    		for (int i = 0; i < args.length; i++) {
    			if (args[i].startsWith("-")) { // Option
    				if (args[i].equals("-incair")) {
    					skipair = false;
    				}
    				else if (args[i].equals("-replaceall")) {
    					replaceall = true;
    				}
    				else if (args[i].startsWith("-yoff:")) {
    					String offval = args[i].substring(args[i].indexOf(':') + 1);
    					try {
    						yoff = Integer.parseInt(offval);
    					} catch (NumberFormatException nfx) {
    						player.printError("Bad y-offset value: " + offval);
    					}
    				}
    				else if (args[i].startsWith("-place:")) {
    					String pval = args[i].substring(args[i].indexOf(':') + 1).toUpperCase();
    					place = Placement.valueOf(pval);
    					if (place == null) {
    						place = Placement.CENTER;
    						player.printError("Bad place value (" + pval + ") - using CENTER");
    					}
    				}
    			}
    		}
    		// Connect to world edit session
    		LocalSession session = wep.getSession((org.spongepowered.api.entity.living.player.Player) sender);
    		SchematicBrushInstance sbi = new SchematicBrushInstance();
    		sbi.set = ss;
    		sbi.player = player;
    		sbi.skipair = skipair;
    		sbi.yoff = yoff;
    		sbi.place = place;
    		sbi.replaceall = replaceall;
    		// Get brush tool
    		BrushTool tool;
    		try {
    			tool = session.getBrushTool(player.getItemInHand());
    			tool.setBrush(sbi, "schematicbrush.brush.use");
    			player.print("Schematic brush set");
    		} catch (InvalidToolBindException e) {
    			player.print(e.getMessage());
    		}
    		return CommandResult.success();
    	}
    }
    	
    public class CommandSCHSET implements CommandExecutor{
    	public CommandSCHSET(){
    	}
    	@Override
    	public CommandResult execute(CommandSource sender, CommandContext ctx) throws CommandException {
    		Collection<String> argstr = ctx.<String>getAll("args");
    		String[] args = argstr.toArray(new String[0]);
    		if (args.length < 1) {  // Not enough arguments
    			sender.sendMessage(Text.of("  <command> argument required: list, create, get, delete, append, remove, setdesc"));
        		return CommandResult.empty();
    		}
    		// Wrap sender
    		Actor player = wep.wrapCommandSource(sender);
    		// Test for command access
    		if (!player.hasPermission("schematicbrush.set." + args[0])) {
    			player.printError("You do not have access to this command");
        		return CommandResult.empty();
    		}
    		boolean rslt = false;
    		if (args[0].equals("list")) {
    			rslt = handleSCHSETList(player, args);
    		}
    		else if (args[0].equals("create")) {
    			rslt = handleSCHSETCreate(player, args);
    		}
    		else if (args[0].equals("delete")) {
    			rslt = handleSCHSETDelete(player, args);
    		}
    		else if (args[0].equals("append")) {
    			rslt = handleSCHSETAppend(player, args);
    		}
    		else if (args[0].equals("get")) {
    			rslt = handleSCHSETGet(player, args);
    		}
    		else if (args[0].equals("remove")) {
    			rslt = handleSCHSETRemove(player, args);
    		}
    		else if (args[0].equals("setdesc")) {
    			rslt = handleSCHSETSetDesc(player, args);
    		}
    		return (rslt?CommandResult.success():CommandResult.empty());
    	}
    }
    
    private boolean handleSCHSETList(Actor player, String[] args) {
        String contains = null;
        if (args.length > 2) {
            contains = args[1];
        }
        int cnt = 0;
        TreeSet<String> keys = new TreeSet<String>(sets.keySet());
        for (String k : keys) {
            if ((contains != null) && (!k.contains(contains))) {
                continue;
            }
            SchematicSet ss = sets.get(k);
            player.print(ss.name + ": desc='" + ss.desc + "'");
            cnt++;
        }
        player.print(cnt + " sets returned");
        
        return true;
    }

    private boolean handleSCHSETCreate(Actor player, String[] args) {
        if (args.length < 2) {
            player.print("Missing set ID");
            return true;
        }
        String setid = args[1];
        if (sets.containsKey(setid)) {  // Existing ID?
            player.print("Set '" + setid + "' already defined");
            return true;
        }
        SchematicSet ss = new SchematicSet(setid, "", null);
        sets.put(setid,  ss);
        // Any other arguments are schematic IDs to add
        for (int i = 2; i < args.length; i++) {
            SchematicDef def = parseSchematic(player, args[i]);
            if (def == null) {
                player.print("Schematic '" + args[i] + "' invalid - ignored");
            }
            else {
                ss.schematics.add(def);
            }
        }
        saveSchematicSets();

        player.print("Set '" + setid + "' created");

        return true;
    }

    private boolean handleSCHSETDelete(Actor player, String[] args) {
        if (args.length < 2) {
            player.print("Missing set ID");
            return true;
        }
        String setid = args[1];
        if (!sets.containsKey(setid)) {  // Existing ID?
            player.print("Set '" + setid + "' not defined");
            return true;
        }
        sets.remove(setid);

        saveSchematicSets();
        
        player.print("Set '" + setid + "' deleted");

        return true;
    }

    private boolean handleSCHSETAppend(Actor player, String[] args) {
        if (args.length < 2) {
            player.print("Missing set ID");
            return true;
        }
        String setid = args[1];
        if (!sets.containsKey(setid)) {  // Existing ID?
            player.print("Set '" + setid + "' not defined");
            return true;
        }
        SchematicSet ss = sets.get(setid);
        // Any other arguments are schematic IDs to add
        for (int i = 2; i < args.length; i++) {
            SchematicDef def = parseSchematic(player, args[i]);
            if (def == null) {
                player.print("Schematic '" + args[i] + "' invalid - ignored");
            }
            else {
                ss.schematics.add(def);
            }
        }
        saveSchematicSets();

        player.print("Set '" + setid + "' updated");

        return true;
    }

    private boolean handleSCHSETRemove(Actor player, String[] args) {
        if (args.length < 2) {
            player.print("Missing set ID");
            return true;
        }
        String setid = args[1];
        if (!sets.containsKey(setid)) {  // Existing ID?
            player.print("Set '" + setid + "' not defined");
            return true;
        }
        SchematicSet ss = sets.get(setid);
        // Any other arguments are schematic IDs to remove
        for (int i = 2; i < args.length; i++) {
            SchematicDef def = parseSchematic(player, args[i]);
            if (def == null) {
                player.print("Schematic '" + args[i] + "' invalid - ignored");
            }
            else {  // Now look for match
                int idx = ss.schematics.indexOf(def);
                if (idx >= 0) {
                    ss.schematics.remove(idx);
                    player.print("Schematic '" + args[i] + "' removed");
                }
                else {
                    player.print("Schematic '" + args[i] + "' not found in set");
                }
            }
        }
        saveSchematicSets();

        player.print("Set '" + setid + "' updated");

        return true;
    }

    private boolean handleSCHSETSetDesc(Actor player, String[] args) {
        if (args.length < 2) {
            player.print("Missing set ID");
            return true;
        }
        String setid = args[1];
        if (!sets.containsKey(setid)) {  // Existing ID?
            player.print("Set '" + setid + "' not defined");
            return true;
        }
        SchematicSet ss = sets.get(setid);
        // Any other arguments are descrption
        String desc = "";
        for (int i = 2; i < args.length; i++) {
            if (i == 2)
                desc = args[i];
            else
                desc = desc + " " + args[i];
        }
        ss.desc = desc;

        saveSchematicSets();

        player.print("Set '" + setid + "' updated");

        return true;
    }

    private boolean handleSCHSETGet(Actor player, String[] args) {
        if (args.length < 2) {
            player.print("Missing set ID");
            return true;
        }
        String setid = args[1];
        if (!sets.containsKey(setid)) {  // Existing ID?
            player.print("Set '" + setid + "' not defined");
            return true;
        }
        SchematicSet ss = sets.get(setid);
        player.print("Description: " + ss.desc);
        for (SchematicDef sd : ss.schematics) {
            String det = sd.name;
            if (sd.format != null) {
                det += ", fmt=" + sd.format;
            }
            if (sd.rotation != Rotation.ROT0) {
                if (sd.rotation == Rotation.RANDOM)
                    det += ", rotate=RANDOM";
                else
                    det += ", rotate=" + (90*sd.rotation.ordinal()) + "\u00B0";
            }
            if (sd.flip != Flip.NONE) {
                det += ", flip=" + sd.flip;
            }
            if (sd.weight > 0) {
                det += ", weight=" + sd.weight;
            }
            player.print("Schematic: " + sd.toString() + " (" + det + ")");
        }
        if ((ss.getTotalWeights() > 100) && (ss.getEqualWeightCount() > 0)) {
            player.print("Warning: total weights exceed 100 - schematics without weights will never be selected");
        }

        return true;
    }
    
    private File getDirectoryForFormat(String fmt) {
        if (fmt.equals("schematic")) {  // Get from worldedit directory
            return new File(wep.getWorkingDir(), wep.getPlatform().getConfiguration().saveDir);
        }
        else {  // Else, our own type specific directory
            return new File(this.configDir, fmt);
        }
    }

    
    private static final int LINES_PER_PAGE = 10;
    
    public class CommandSCHLIST implements CommandExecutor{
    	public CommandSCHLIST(){
    	}
    	@Override
    	public CommandResult execute(CommandSource sender, CommandContext ctx) throws CommandException {
    		Collection<String> argstr = ctx.<String>getAll("args");
    		String[] args = argstr.toArray(new String[0]);
    		// Wrap sender
    		Actor actor = wep.wrapCommandSource(sender);
    		if ((actor instanceof Player) == false) {
    			actor.printError("Only usable by player");
    			return CommandResult.empty();
    		}
    		Player player = (Player) actor;
    		// Test for command access
    		if (!player.hasPermission("schematicbrush.list")) {
    			player.printError("You do not have access to this command");
    			return CommandResult.empty();
    		}
    		int page = 1;
    		String fmt = "schematic";
    		for (int i = 0; i < args.length; i++) {
    			try {
    				page = Integer.parseInt(args[i]);
    			} catch (NumberFormatException nfx) {
    				fmt = args[i];
    			}
    		}
    		File dir = getDirectoryForFormat(fmt);  // Get directory for extension
    		if (dir == null) {
    			actor.printError("Invalid format: " + fmt);
    			return CommandResult.empty();
    		}
    		final Pattern p = Pattern.compile(".*\\." + fmt);
    		List<String> files = getMatchingFiles(dir, p);
    		Collections.sort(files);
    		int cnt = (files.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE;  // Number of pages
    		if (page > cnt) page = cnt;
    		if (page < 1) page = 1;
    		actor.print("Page " + page + " of " + cnt + " (" + files.size() + " files)");
    		for (int i = (page - 1) * LINES_PER_PAGE; (i < (page * LINES_PER_PAGE)) && (i < files.size()); i++) {
    			actor.print(files.get(i));
    		}
    		return CommandResult.success();
    	}
    }
        
    private static final Pattern schsplit = Pattern.compile("[@:#^]");
    
    private SchematicDef parseSchematic(Actor player, String sch) {
        String[] toks = schsplit.split(sch, 0);
        final String name = toks[0];  // Name is first
        String formatName = "schematic";
        Rotation rot = Rotation.ROT0;
        Flip flip = Flip.NONE;
        int wt = DEFAULT_WEIGHT;
        int offset = 0;
        
        for (int i = 1, off = toks[0].length(); i < toks.length; i++) {
            char sep = sch.charAt(off);
            off = off + 1 + toks[i].length();
            if (sep == '@') { // Rotation/flip?
                String v = toks[i];
                if (v.startsWith("*")) {  // random rotate?
                    rot = Rotation.RANDOM;
                    v = v.substring(1);
                }
                else {  // Else, must be number
                    rot = Rotation.ROT0;
                    int coff;
                    int val = 0;
                    for (coff = 0; coff < v.length(); coff++) {
                        if (Character.isDigit(v.charAt(coff))) {
                            val = (val * 10) + (v.charAt(coff) - '0');
                        }
                        else {
                            break;
                        }
                    }
                    // If not multiple of 90, error
                    if ((val % 90) != 0) {
                        return null;
                    }
                    rot = Rotation.values()[((val / 90) % 4)];    // Clamp to 0-270
                    v = v.substring(coff);
                }
                if (v.length() == 0) {
                    flip = Flip.NONE;
                }
                else {
                    char c = v.charAt(0);
                    switch (c) {
                        case '*':
                            flip = Flip.RANDOM;
                            break;
                        case 'N':
                        case 'S':
                        case 'n':
                        case 's':
                            flip = Flip.NS;
                            break;
                        case 'E':
                        case 'W':
                        case 'e':
                        case 'w':
                            flip = Flip.EW;
                            break;
                        default:
                            return null;
                    }
                }
            }
            else if (sep == ':') { // weight
                try {
                    wt = Integer.parseInt(toks[i]);
                } catch (NumberFormatException nfx) {
                    return null;
                }
            }
            else if (sep == '#') { // format name
                formatName = toks[i];
            }
            else if (sep == '^') { // Offset
                try {
                    offset = Integer.parseInt(toks[i]);
                } catch (NumberFormatException nfx) {
                    return null;
                }
            }
        }
        // See if schematic name is valid
        File dir = getDirectoryForFormat(formatName);
        try {
            String fname = this.resolveName(player, dir, name, formatName);
            if (fname == null) {
                return null;
            }
            File f = we.getSafeOpenFile(null, dir, fname, formatName);
            if (!f.exists()) {
                return null;
            }
            if ((formatName.equals("schematic") == false) && (formatName.equals("bo2") == false)) {
                return null;
            }
            // If we're here, everything is good - make schematic object
            SchematicDef schematic = new SchematicDef();
            schematic.name = name;
            schematic.format = formatName;
            schematic.rotation = rot;
            schematic.flip = flip;
            schematic.weight = wt;
            schematic.offset = offset;
            
            return schematic;
        } catch (FilenameException fx) {
            return null;
        }
    }
    
    private void loadSchematicSets() {
        sets.clear(); // Reset sets
        
        Actor console = wep.wrapCommandSource(Sponge.getServer().getConsole());

        ConfigurationNode sect = configNode.getNode("schematic-sets");
        if (sect == null) {
            return;
        }
        Map<Object, ? extends ConfigurationNode> map = sect.getChildrenMap();
        for (Entry<Object, ? extends ConfigurationNode> entset : map.entrySet()) {
        	String key = entset.getKey().toString();
        	logger.info("Process " + key + "...");
        	ConfigurationNode schset = entset.getValue();
            if (schset == null) continue;
            String desc = schset.getNode("desc").getString("");
            List<String> schematicsets;
			try {
				schematicsets = schset.getNode("sets").getList(TypeToken.of(String.class));
			} catch (ObjectMappingException e) {
				schematicsets = Collections.emptyList();
			}
            if (schematicsets == null) continue;
            ArrayList<SchematicDef> schlist = new ArrayList<SchematicDef>();
            for (String set: schematicsets) {
                SchematicDef def = parseSchematic(console, set);
                if (def != null) {
                    schlist.add(def);
                }
                else {
                    logger.warn("Error loading schematic '" + set + "' for set '" + key + "' - removed from set");
                }
            }
            if (schlist.isEmpty()) {
                logger.warn("Error loading schematic set '" + key + "' - no valid schematics - removed");
                continue;
            }
            // Build schematic set
            SchematicSet ss = new SchematicSet(key, desc, schlist);
            sets.put(key, ss);
        }
    }
    private void saveSchematicSets() {        
        ConfigurationNode sect = configNode.getNode("schematic-sets");
        for (SchematicSet ss : sets.values()) {
        	ConfigurationNode newnode = sect.getNode(ss.name);
        	newnode.getNode("desc").setValue(ss.desc);
        	ArrayList<String> sets = new ArrayList<String>();
        	for (SchematicDef s : ss.schematics) {
        		sets.add(s.toString());
        	}
        	newnode.getNode("sets").setValue(sets);
        }
        // Remove missing children
        Map<Object, ? extends ConfigurationNode> map = sect.getChildrenMap();
        for (Object key : map.keySet()) {
        	if (sets.containsKey(key) == false) {
        		sect.removeChild(key);
        	}
        }
        try {
			configManager.save(configNode);
		} catch (IOException e) {
            logger.warn("Error saving configuration");
		}
    }    

    private List<String> getMatchingFiles(File dir, Pattern p) {
        ArrayList<String> matches = new ArrayList<String>();
        getMatchingFiles(matches, dir, p, null);
        return matches;
    }
    
    // Schematic tree cache - used during initialization
    private Map<File, List<String>> treecache = new HashMap<File, List<String>>();
    
    private void buildTree(File dir, List<String> rslt, String path) {
    	File[] lst = dir.listFiles();
    	for (File f : lst) {
            String n = (path == null) ? f.getName() : (path + "/" + f.getName());
    		if (f.isDirectory()) {
    			buildTree(f, rslt, n);
    		}
    		else {
    			rslt.add(n);
    		}
    	}
    }
    
    private void getMatchingFiles(List<String> rslt, File dir, final Pattern p, final String path) {
    	List<String> flist = null;
    	if (treecache != null) {
    		flist = treecache.get(dir);	// See if cached
    	}
    	if (flist == null) {
    		flist = new ArrayList<String>();
    		buildTree(dir, flist, null);
    		if (treecache != null) {
    			treecache.put(dir, flist);
    		}
    	}
    	for (String fn : flist) {
    		if (p.matcher(fn).matches()) {
                rslt.add(fn);
            }
        }
    }

    /* Resolve name to loadable name - if contains wildcards, pic random matching file */
    private String resolveName(Actor player, File dir, String fname, final String ext) {
        // If command-line style wildcards
        if ((!fname.startsWith("^")) && ((fname.indexOf('*') >= 0) || (fname.indexOf('?') >= 0))) {
            // Compile to regex
            fname = "^" + fname.replace(".","\\.").replace("*",  ".*").replace("?", ".");
        }
        if (fname.startsWith("^")) { // If marked as regex
            final int extlen = ext.length();
            try {
                final Pattern p = Pattern.compile(fname + "\\." + ext);
                List<String> files = getMatchingFiles(dir, p);
                if (files.isEmpty() == false) {    // Multiple choices?
                    String n = files.get(rnd.nextInt(files.size()));
                    n = n.substring(0, n.length() - extlen - 1);
                    return n;
                }
                else {
                    return null;
                }
            } catch (PatternSyntaxException x) {
                player.printError("Invalid filename pattern - " + fname + " - " + x.getMessage());
                return null;
            }
        }
        return fname;
    }
    
    private String loadSchematicIntoClipboard(Player player, LocalSession sess, String fname, String format, int[] bottomY) {
        File dir = getDirectoryForFormat(format);
        if (dir == null) {
            player.printError("Schematic '" + fname + "' invalid format - " + format);
            return null;
        }
        String name = resolveName(player, dir, fname, format);
        if (name == null) {
            player.printError("Schematic '" + fname + "' file not found");
            return null;
        }
        File f;
        boolean rslt = false;
        Closer closer = Closer.create();
        try {
            f = we.getSafeOpenFile(null, dir, name, format);
            if (!f.exists()) {
                player.printError("Schematic '" + name + "' file not found");
                return null;
            }
            // Figure out format to use
            if (format.equals("schematic")) {
                ClipboardFormat fmt = ClipboardFormat.findByFile(f);

                if (fmt == null) {
                    player.printError("Schematic '" + name + "' format not found");
                    return null;
                }
                if (!fmt.isFormat(f)) {
                    player.printError("Schematic '" + name + "' is not correct format (" + fmt + ")");
                    return null;
                }
                String filePath = f.getCanonicalPath();
                String dirPath = dir.getCanonicalPath();

                if (!filePath.substring(0, dirPath.length()).equals(dirPath)) {
                    return null;
                } else {
                    FileInputStream fis = closer.register(new FileInputStream(f));
                    BufferedInputStream bis = closer.register(new BufferedInputStream(fis));
                    ClipboardReader reader = fmt.getReader(bis);

                    WorldData worldData = player.getWorld().getWorldData();
                    Clipboard cc = reader.read(player.getWorld().getWorldData());
                    if (cc != null) {
                        Region reg = cc.getRegion();
                        int minY = reg.getHeight() - 1;
                        for (int y = 0; (minY == -1) && (y < reg.getHeight()); y++) {
                            for (int x = 0; (minY == -1) && (x < reg.getWidth()); x++) {
                                for (int z = 0; (minY == -1) && (z < reg.getLength()); z++) {
                                    if (cc.getBlock(new Vector(x, y, z)) != null) {
                                        minY = y;
                                        break;
                                    }
                                }
                            }
                        }
                        bottomY[0] = minY;
                        sess.setClipboard(new ClipboardHolder(cc, worldData));
                        rslt = true;
                    }
                }
            }
            // Else if BO2 file
            else if (format.equals("bo2")) {
                Clipboard cc = loadBOD2File(f);
                if (cc != null) {
                    WorldData worldData = player.getWorld().getWorldData();
                    sess.setClipboard(new ClipboardHolder(cc, worldData));
                    rslt = true;
                    bottomY[0] = 0; // Always zero for these: we compact things to bottom
                }
            }
            else {
                return null;
            }
        } catch (FilenameException e1) {
            player.printError(e1.getMessage());
        } catch (IOException e) {
            player.printError("Error reading schematic '" + name + "' - " + e.getMessage());
        } finally {
            try {
                closer.close();
            } catch (IOException ignored) {
            }
        }

        return (rslt)?name:null;
    }
    
    private Clipboard loadBOD2File(File f) throws IOException {
        Clipboard cc = null;
        
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("US-ASCII")));
        try {
            Map<String, String> properties = new HashMap<String, String>();
            Map<Vector, int[]> blocks = new HashMap<Vector, int[]>();
            boolean readingMetaData = false, readingData = false;
            String line;
            int lowestX = Integer.MAX_VALUE, highestX = Integer.MIN_VALUE;
            int lowestY = Integer.MAX_VALUE, highestY = Integer.MIN_VALUE;
            int lowestZ = Integer.MAX_VALUE, highestZ = Integer.MIN_VALUE;
            while ((line = in.readLine()) != null) {
                if (line.trim().length() == 0) {
                    continue;
                }
                if (readingMetaData) {
                    if (line.equals("[DATA]")) {
                        readingMetaData = false;
                        readingData = true;
                    } else {
                        int p = line.indexOf('=');
                        String name = line.substring(0, p).trim();
                        String value = line.substring(p + 1).trim();
                        properties.put(name, value);
                    }
                } else if (readingData) {
                    int p = line.indexOf(':');
                    String coordinates = line.substring(0, p);
                    String spec = line.substring(p + 1);
                    p = coordinates.indexOf(',');
                    int x = Integer.parseInt(coordinates.substring(0, p));
                    int p2 = coordinates.indexOf(',', p + 1);
                    int y = Integer.parseInt(coordinates.substring(p + 1, p2));
                    int z = Integer.parseInt(coordinates.substring(p2 + 1));
                    p = spec.indexOf('.');
                    int blockId, data = 0;
                    int[] branch = null;
                    if (p == -1) {
                        blockId = Integer.parseInt(spec);
                    } else {
                        blockId = Integer.parseInt(spec.substring(0, p));
                        p2 = spec.indexOf('#', p + 1);
                        if (p2 == -1) {
                            data = Integer.parseInt(spec.substring(p + 1));
                        } else {
                            data = Integer.parseInt(spec.substring(p + 1, p2));
                            p = spec.indexOf('@', p2 + 1);
                            branch = new int[] {Integer.parseInt(spec.substring(p2 + 1, p)), Integer.parseInt(spec.substring(p + 1))};
                        }
                    }
                    if (blockId == 0) continue; // Skip air blocks;
                    
                    if (x < lowestX) {
                        lowestX = x;
                    }
                    if (x > highestX) {
                        highestX = x;
                    }
                    if (y < lowestY) {
                        lowestY = y;
                    }
                    if (y > highestY) {
                        highestY = y;
                    }
                    if (z < lowestZ) {
                        lowestZ = z;
                    }
                    if (z > highestZ) {
                        highestZ = z;
                    }
                    Vector coords = new Vector(x, y, z);
                    blocks.put(coords, new int[] { blockId, data } );
                } else {
                    if (line.equals("[META]")) {
                        readingMetaData = true;
                    }
                }
            }
            Vector size = new Vector(highestX - lowestX + 1, highestZ - lowestZ + 1, highestY - lowestY + 1);
            Vector offset = new Vector(-lowestX, -lowestZ, -lowestY);
            Region reg = new CuboidRegion(size, offset);
            cc = new BlockArrayClipboard(reg);
            for (Vector v : blocks.keySet()) {
                int[] ids = blocks.get(v);
                Vector vv = new Vector(v.getX() - lowestX, v.getZ() - lowestZ, v.getY() - lowestY);
                cc.setBlock(vv, new BaseBlock(ids[0], ids[1]));
            }
        } catch (WorldEditException e) {
            logger.info("WorldEdit exception: " + e.getMessage());
        } finally {
            in.close();
        }

        
        return cc;
    }
}
