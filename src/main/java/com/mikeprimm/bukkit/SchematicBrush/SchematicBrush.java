package com.mikeprimm.bukkit.SchematicBrush;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.minecraft.util.commands.CommandsManager;
import com.sk89q.worldedit.masks.BlockMask;
import com.sk89q.worldedit.schematic.SchematicFormat;
import com.sk89q.worldedit.tools.BrushTool;
import com.sk89q.worldedit.tools.brushes.Brush;
import com.sk89q.worldedit.CuboidClipboard;
import com.sk89q.worldedit.CuboidClipboard.FlipDirection;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.FilenameException;
import com.sk89q.worldedit.InvalidToolBindException;
import com.sk89q.worldedit.LocalPlayer;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.data.DataException;

public class SchematicBrush extends JavaPlugin {
    public Logger log;
    public WorldEdit we;
    public WorldEditPlugin wep;
    public static final int DEFAULT_WEIGHT = -1;

    public static enum Flip {
        NONE, NS, EW, RANDOM;
    }
    public static enum Rotation {
        ROT0, ROT90, ROT180, ROT270, RANDOM
    }
    
    public CommandsManager<CommandSender> cmdmgr;
    private static Random rnd = new Random();

    public static class SchematicDef {
        public String name;
        public String format;
        public Rotation rotation;
        public Flip flip;
        public int weight;      // If -1, equal weight with other -1 schematics
        @Override
        public boolean equals(Object o) {
            if (o instanceof SchematicDef) {
                SchematicDef sd = (SchematicDef) o;
                return sd.name.equals(this.name) && (sd.rotation == this.rotation) && (sd.flip == this.flip) && (sd.weight == this.weight);
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
        LocalPlayer player;
        boolean skipair;
        int yoff;
        
        public void build(EditSession editsession, Vector pos,
                com.sk89q.worldedit.patterns.Pattern mat, double size)
                throws MaxChangedBlocksException {
            SchematicDef def = set.getRandomSchematic();    // Pick schematic from set
            if (def == null) return;
            LocalSession sess = we.getSession(player);
            if (!loadSchematicIntoClipboard(player, sess, def.name, def.format)) {
                return;
            }
            CuboidClipboard clip = null;
            try {
                clip = sess.getClipboard();
            } catch (EmptyClipboardException e) {
                player.printError("Schematic is empty");
                return;
            }
            // Get rotation for clipboard
            Rotation rot = def.getRotation();
            if (rot != Rotation.ROT0) {
                clip.rotate2D(rot.ordinal() * 90);
            }
            // Get flip option
            Flip flip = def.getFlip();
            switch (flip) {
                case NS:
                    clip.flip(FlipDirection.NORTH_SOUTH);
                    break;
                case EW:
                    clip.flip(FlipDirection.WEST_EAST);
                    break;
                default:
                    break;
            }
            // And apply clipboard to edit session
            Vector csize = clip.getSize();
            clip.place(editsession, pos.subtract(csize.getX() / 2, -yoff, csize.getZ() / 2), skipair);
        }
    }
   
    public SchematicBrush() {
    }
    
    /* On disable, stop doing our function */
    public void onDisable() {
        
    }

    public void onEnable() {
        log = this.getLogger();
        
        log.info("SchematicBrush v" + this.getDescription().getVersion() + " loaded");

        final FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
    
        Plugin wedit = this.getServer().getPluginManager().getPlugin("WorldEdit");
        if (wedit == null) {
            log.info("WorldEdit not found!");
            return;
        }
        wep = (WorldEditPlugin) wedit;
        we = wep.getWorldEdit();
        // Load existing schematics
        loadSchematicSets();
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        if (cmd.getName().equals("/schbr")) {
            handleSCHBRCommand(sender, cmd, args);
            return true;
        }
        else if (cmd.getName().equals("/schset")) {
            handleSCHSETCommand(sender, cmd, args);
            return true;
        }
        return false;
    }    
    
    private boolean handleSCHBRCommand(CommandSender sender, Command cmd, String[] args) {
        LocalPlayer player = wep.wrapCommandSender(sender);
        if (args.length < 1) {
            player.print("Schematic brush requires &set-id or one or more schematic patterns");
            return false;
        }
        String setid = null;
        SchematicSet ss = null;
        if (args[0].startsWith("&")) {  // If set ID
            if (player.hasPermission("schematicbrush.set.use") == false) {
                player.printError("Not permitted to use schematic sets");
                return true;
            }
            setid = args[0].substring(1);
            ss = sets.get(setid);
            if (ss == null) {
                player.print("Schematic set '" + setid + "' not found");
                return true;
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
                        return true;
                    }
                    defs.add(sd);
                }
            }
            ss = new SchematicSet(null, null, defs);
        }
        boolean skipair = true;
        boolean replaceall = false;
        int yoff = 1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) { // Option
                if (args[i].equals("-incair")) {
                    skipair = false;
                }
                else if (args[i].equals("-replaceall")) {
                    replaceall = true;
                }
                else if (args[i].startsWith("-yoff:")) {
                    String offval = args[i].substring(6);
                    try {
                        yoff = Integer.parseInt(offval);
                    } catch (NumberFormatException nfx) {
                        player.printError("Bad y-offset value: " + offval);
                    }
                }
            }
        }
        // Connect to world edit session
        LocalSession session = we.getSession(player);

        SchematicBrushInstance sbi = new SchematicBrushInstance();
        sbi.set = ss;
        sbi.player = player;
        sbi.skipair = skipair;
        sbi.yoff = yoff;
        // Get brush tool
        BrushTool tool;
        try {
            tool = session.getBrushTool(player.getItemInHand());
            tool.setBrush(sbi, "schematicbrush.brush.use");
            if (!replaceall) {
                tool.setMask(new BlockMask(new BaseBlock(BlockID.AIR)));
            }
            player.print("Schematic brush set");
        } catch (InvalidToolBindException e) {
            player.print(e.getMessage());
        }
        
        return true;
    }

    private boolean handleSCHSETCommand(CommandSender sender, Command cmd, String[] args) {
        if (args.length < 1) {  // Not enough arguments
            sender.sendMessage("  <command> argument required: list, create, get, delete, append, remove, setdesc");
            return false;
        }
        // Wrap sender
        LocalPlayer player = wep.wrapCommandSender(sender);
        // Test for command access
        if (!player.hasPermission("schematicbrush.set." + args[0])) {
            sender.sendMessage("You do not have access to this command");
            return true;
        }
        if (args[0].equals("list")) {
            return handleSCHSETList(player, args);
        }
        else if (args[0].equals("create")) {
            return handleSCHSETCreate(player, args);
        }
        else if (args[0].equals("delete")) {
            return handleSCHSETDelete(player, args);
        }
        else if (args[0].equals("append")) {
            return handleSCHSETAppend(player, args);
        }
        else if (args[0].equals("get")) {
            return handleSCHSETGet(player, args);
        }
        else if (args[0].equals("remove")) {
            return handleSCHSETRemove(player, args);
        }
        else if (args[0].equals("setdesc")) {
            return handleSCHSETSetDesc(player, args);
        }
        
        return false;
    }
    
    private boolean handleSCHSETList(LocalPlayer player, String[] args) {
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

    private boolean handleSCHSETCreate(LocalPlayer player, String[] args) {
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

    private boolean handleSCHSETDelete(LocalPlayer player, String[] args) {
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

    private boolean handleSCHSETAppend(LocalPlayer player, String[] args) {
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

    private boolean handleSCHSETRemove(LocalPlayer player, String[] args) {
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

    private boolean handleSCHSETSetDesc(LocalPlayer player, String[] args) {
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

    private boolean handleSCHSETGet(LocalPlayer player, String[] args) {
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

    private static final Pattern schsplit = Pattern.compile("[@:#]");
    
    private SchematicDef parseSchematic(LocalPlayer player, String sch) {
        String[] toks = schsplit.split(sch, 0);
        final String name = toks[0];  // Name is first
        String formatName = null;
        Rotation rot = Rotation.ROT0;
        Flip flip = Flip.NONE;
        int wt = DEFAULT_WEIGHT;
        
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
        }
        // See if schematic name is valid
        File dir = we.getWorkingDirectoryFile(we.getConfiguration().saveDir);
        try {
            String fname = this.resolveName(player, dir, name, "schematic");
            if (fname == null) {
                return null;
            }
            File f = we.getSafeOpenFile(player, dir, fname, "schematic", "schematic");
            if (!f.exists()) {
                return null;
            }
            SchematicFormat format = formatName == null ? null : SchematicFormat.getFormat(formatName);
            if (format == null) {
                format = SchematicFormat.getFormat(f);
            }
            if (format == null) {
                return null;
            }
            // If we're here, everything is good - make schematic object
            SchematicDef schematic = new SchematicDef();
            schematic.name = name;
            schematic.format = formatName;
            schematic.rotation = rot;
            schematic.flip = flip;
            schematic.weight = wt;
            
            return schematic;
        } catch (FilenameException fx) {
            return null;
        }
    }
    
    private void loadSchematicSets() {
        sets.clear(); // Reset sets
        
        LocalPlayer console = wep.wrapCommandSender(getServer().getConsoleSender());
        FileConfiguration cfg = this.getConfig();
        ConfigurationSection sect = cfg.getConfigurationSection("schematic-sets");
        if (sect == null) {
            return;
        }
        Set<String> keys = sect.getKeys(false);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            ConfigurationSection schset = sect.getConfigurationSection(key);
            if (schset == null) continue;
            String desc = schset.getString("desc","");
            List<String> schematicsets = schset.getStringList("sets");
            if (sets == null) continue;
            ArrayList<SchematicDef> schlist = new ArrayList<SchematicDef>();
            for (String set: schematicsets) {
                SchematicDef def = parseSchematic(console, set);
                if (def != null) {
                    schlist.add(def);
                }
                else {
                    getLogger().warning("Error loading schematic '" + set + "' for set '" + key + "' - removed from set");
                }
            }
            if (schlist.isEmpty()) {
                getLogger().warning("Error loading schematic set '" + key + "' - no valid schematics - removed");
                continue;
            }
            // Build schematic set
            SchematicSet ss = new SchematicSet(key, desc, schlist);
            sets.put(key, ss);
        }
    }
    private void saveSchematicSets() {        
        FileConfiguration cfg = this.getConfig();
        ConfigurationSection sect = cfg.getConfigurationSection("schematic-sets");
        if (sect == null) {
            sect = cfg.createSection("schematic-sets");
        }
        for (SchematicSet ss : sets.values()) {
            sect.set(ss.name + ".desc", ss.desc);
            ArrayList<String> lst = new ArrayList<String>();
            for (SchematicDef sd : ss.schematics) {
                lst.add(sd.toString());
            }
            sect.set(ss.name + ".sets", lst);
        }
        for (String k : sect.getKeys(false)) {
            if (sets.containsKey(k) == false) { // No longer good set?
                sect.set(k, null);
            }
        }
        cfg.set("schematic-sets",  sect);
        
        this.saveConfig();
    }    
    /* Resolve name to loadable name - if contains wildcards, pic random matching file */
    private String resolveName(LocalPlayer player, File dir, String fname, final String ext) {
        // If command-line style wildcards
        if ((!fname.startsWith("^")) && ((fname.indexOf('*') >= 0) || (fname.indexOf('?') >= 0))) {
            // Compile to regex
            fname = "^" + fname.replace(".","\\.").replace("*",  ".*").replace("?", ".");
        }
        if (fname.startsWith("^")) { // If marked as regex
            final int extlen = ext.length();
            try {
                final Pattern p = Pattern.compile(fname + "\\." + ext);
                String[] files = dir.list(new FilenameFilter() {
                    public boolean accept(File f, String fn) {
                        Matcher m = p.matcher(fn);
                        return m.matches();
                    }
                });
                if ((files != null) && (files.length > 0)) {    // Multiple choices?
                    String n = files[rnd.nextInt(files.length)];
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
    
    private boolean loadSchematicIntoClipboard(LocalPlayer player, LocalSession sess, String fname, String format) {
        File dir = we.getWorkingDirectoryFile(we.getConfiguration().saveDir);
        String name = resolveName(player, dir, fname, "schematic");
        if (name == null) {
            player.printError("Schematic '" + fname + "' file not found");
        }
        File f;
        boolean rslt = false;
        try {
            f = we.getSafeOpenFile(player, dir, name, "schematic", "schematic");
            if (!f.exists()) {
                player.printError("Schematic '" + name + "' file not found");
                return false;
            }
            // Figure out format to use
            SchematicFormat fmt = format == null ? null : SchematicFormat.getFormat(format);
            if (fmt == null) {
                fmt = SchematicFormat.getFormat(f);
            }
            if (fmt == null) {
                player.printError("Schematic '" + name + "' format not found");
                return false;
            }
            if (!fmt.isOfFormat(f)) {
                player.printError("Schematic '" + name + "' is not correct format (" + fmt.getName() + ")");
                return false;
            }
            String filePath = f.getCanonicalPath();
            String dirPath = dir.getCanonicalPath();

            if (!filePath.substring(0, dirPath.length()).equals(dirPath)) {
                return false;
            } else {
                sess.setClipboard(fmt.load(f));
                rslt = true;
            }
        } catch (FilenameException e1) {
            player.printError(e1.getMessage());
        } catch (DataException e) {
            player.printError("Error loading schematic '" + name + "'");
        } catch (IOException e) {
            player.printError("Error reading schematic '" + name + "' - " + e.getMessage());
        }

        return rslt;
    }
}
