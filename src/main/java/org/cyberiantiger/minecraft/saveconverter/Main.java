/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cyberiantiger.minecraft.saveconverter;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.cyberiantiger.minecraft.nbt.CompoundTag;
import org.cyberiantiger.minecraft.nbt.Tag;
import org.cyberiantiger.minecraft.nbt.TagInputStream;
import org.cyberiantiger.minecraft.nbt.TagOutputStream;
import org.cyberiantiger.minecraft.nbt.TagType;

/**
 *
 * @author antony
 */
public class Main {
    private static final URL PROFILE_URL;
    static {
        try {
            PROFILE_URL = new URL("https://api.mojang.com/profiles/minecraft");
        } catch (MalformedURLException ex) {
            throw new IllegalStateException();
        }
    }
    private static final Pattern VALID_USERNAME = Pattern.compile("[a-zA-Z0-9_]{2,16}");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int BATCH_SIZE = 100; // Mojang's code says don't do over 100 at once.
    private static final Gson gson = new Gson();


    private static void usage() {
        System.err.println("Usage: java -jar save-converter.jar [-n] [-r] [-o] <worldsave> <worldsave>....");
        System.err.println("     -n - dry run, don't do anything.");
        System.err.println("     -r - downgrade save files.");
        System.err.println("     -o - offline mode");
    }

    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<String>();
        boolean parseFlags = true;
        boolean dryRun = false;
        boolean reverse = false;
        boolean offline = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (parseFlags && arg.length() > 0 && arg.charAt(0) == '-') {
                if ("--".equals(args)) {
                    parseFlags = false;
                } else if ("-n".equals(arg)) {
                    dryRun = true;
                } else if ("-r".equals(arg)) {
                    reverse = true;
                } else if ("-o".equals(arg)) {
                    offline = true;
                } else {
                    System.err.println("Unexpected flag: " + arg);
                    usage();
                    System.exit(1);
                }
                continue;
            }
            paths.add(arg);
        }

        if (paths.isEmpty()) {
            usage();
            System.exit(0);
        }

        for (String s : paths) {
            File file = new File(s);
            if (!file.isDirectory()) {
                System.out.println("# Skipping "  + file.getPath() + " - not a directory");
                continue;
            } else if (!file.canRead()) {
                System.out.println("# Skipping "  + file.getPath() + " - cannot read");
                continue;
            }
            if (reverse) {
                downgradePlayerFiles(file, dryRun, offline);
            } else {
                upgradePlayerFiles(file, dryRun, offline);
            }
        }
    }

    private static boolean isOfflineUUID(String player, UUID uuid) {
        return getOfflineUUID(player).equals(uuid);
    }

    private static UUID getOfflineUUID(String player) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(UTF8));
    }

    private static Map<String,UUID> getOnlineUUIDs(Collection<String> tmp) throws IOException {
        List<String> players = new ArrayList<String>(tmp);
        List<String> batch = new ArrayList<String>();
        Map<String,UUID> result = new HashMap<String,UUID>();
        while (!players.isEmpty()) {
            for (int i = 0; !players.isEmpty() && i < 100; i++) {
                batch.add(players.remove(players.size()-1));
            }
            HttpURLConnection connection = (HttpURLConnection) PROFILE_URL.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type","application/json; encoding=UTF-8");
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(gson.toJson(batch).getBytes(UTF8));
            out.close();
            Reader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            Profile[] profiles = gson.fromJson(in, Profile[].class);
            for (Profile profile : profiles) {
                result.put(profile.getName(), profile.getUUID());
            }
            batch.clear();
        }
        return result;
    }

    private static void upgradePlayerFiles(File file, boolean dryRun, boolean offline) {
        File playerDir = new File(file, "players");
        if (!playerDir.isDirectory()) {
            System.out.println("# Skipping "  + file.getPath() + " - no players dir");
            return;
        } else if (!playerDir.canRead()) {
            System.out.println("# Skipping "  + file.getPath() + " - players dir cannot be read");
            return;
        }
        File playerdataDir = new File(file, "playerdata");
        if (!playerdataDir.exists()) {
            if (!dryRun) {
                if (!playerdataDir.mkdir()) {
                    System.out.println("Skipping "  + file.getPath() + " - could not create playerdata directory");
                    return;
                }
            }
        } else {
            if (!playerdataDir.isDirectory()) {
                System.out.println("Skipping "  + file.getPath() + " - playerdata not a directory");
                return;
            } else if (!playerdataDir.canRead()) {
                System.out.println("Skipping "  + file.getPath() + " - playerdata cannot be read");
                return;
            }
        }
        Map<String, Tag> playersaves = new HashMap<String,Tag>();
        System.out.println("Players dir: " + playerDir.getPath());
        System.out.println("Playerdata dir: " + playerdataDir.getPath());
        for (File playerFile : playerDir.listFiles()) {
            System.out.println("Processing " + playerFile.getPath());
            if (!playerFile.isFile()) {
                System.out.println("Skipping "  + playerFile.getPath() + " - not a regular file");
                continue;
            }
            String name = playerFile.getName();
            if (!name.endsWith(".dat")) {
                System.out.println("Skipping "  + playerFile.getPath() + " - does not end in .dat");
                continue;
            }
            String playerName = name.substring(0, name.length() - ".dat".length());
            if (!VALID_USERNAME.matcher(playerName).matches()) {
                System.out.println("Skipping "  + playerFile.getPath() + " - does not look like a minecraft username");
                continue;
            }
            Tag playerData;
            try {
                TagInputStream in = new TagInputStream(new GZIPInputStream(new FileInputStream(playerFile)));
                try {
                    playerData = in.readTag();
                } finally {
                    in.close();
                }
            } catch (IOException ex) {
                System.out.println("Skipping " + playerFile.getPath() + " - could not parse file");
                continue;
            }
            if (playerData.getType() != TagType.COMPOUND) {
                System.out.println("Skipping "  + playerFile.getPath() + " - contains non compound nbt tag as root");
                continue;
            }
            CompoundTag playerDataCompound = (CompoundTag) playerData;
            CompoundTag bukkit;
            if (playerDataCompound.containsKey("bukkit")) {
                bukkit = playerDataCompound.getCompound("bukkit");
            } else {
                playerDataCompound.setCompound("bukkit", bukkit = new CompoundTag("bukkit"));
            }
            bukkit.setString("lastKnownName", playerName);
            playersaves.put(playerName, playerData);
        }
        Map<String,UUID> uuids;
        if (offline) {
            uuids = new HashMap<String, UUID>();
            for (String s : playersaves.keySet()) {
                uuids.put(s, getOfflineUUID(s));
            }
        } else {
            try {
                uuids = getOnlineUUIDs(playersaves.keySet());
            } catch (IOException ex) {
                System.out.println("Catastrophic failure of mojang profile api, try again later.");
                return;
            }
        }
        for (Map.Entry<String, Tag> e : playersaves.entrySet()) {
            File playerFile = new File(playerDir, e.getKey() + ".dat");
            if (!uuids.containsKey(e.getKey())) {
                System.out.println("Could not lookup uuid for " + playerFile + " skipping ... ");
                continue;
            }
            UUID uuid = uuids.get(e.getKey());
            File targetFile = new File(playerdataDir, uuid.toString() + ".dat");
            if (targetFile.exists()) {
                System.out.println("Skipping " + playerFile + " - target save already exists: " + targetFile);
                continue;
            }
            if (dryRun) {
                System.out.println("Would move " + playerFile + " to " + targetFile);
            } else {
                try {
                    TagOutputStream out = new TagOutputStream(new GZIPOutputStream(new FileOutputStream(targetFile)));
                    try {
                        out.writeTag(e.getValue());
                    } finally {
                        out.close();
                    }
                } catch (IOException ex) {
                    System.out.println("Skipping "  + e.getKey() + " - failed to write target file");
                    targetFile.delete();
                    continue;
                }
            }
        }
    }

    private static void downgradePlayerFiles(File file, boolean dryRun, boolean offline) {
        File playerdataDir = new File(file, "playerdata");
        if (!playerdataDir.isDirectory()) {
            System.out.println("# Skipping "  + file.getPath() + " - no players dir");
            return;
        } else if (!playerdataDir.canRead()) {
            System.out.println("# Skipping "  + file.getPath() + " - players dir cannot be read");
            return;
        }
        File playerDir = new File(file, "players");
        if (!playerDir.exists()) {
            if (!dryRun) {
                if (!playerDir.mkdir()) {
                    System.out.println("Skipping "  + file.getPath() + " - could not create playerdata directory");
                    return;
                }
            }
        } else {
            if (!playerDir.isDirectory()) {
                System.out.println("Skipping "  + file.getPath() + " - playerdata not a directory");
                return;
            } else if (!playerDir.canRead()) {
                System.out.println("Skipping "  + file.getPath() + " - playerdata cannot be read");
                return;
            }
        }
        System.out.println("Players dir: " + playerDir.getPath());
        System.out.println("Playerdata dir: " + playerdataDir.getPath());
        for (File playerFile : playerdataDir.listFiles()) {
            System.out.println("Processing " + playerFile.getPath());
            if (!playerFile.isFile()) {
                System.out.println("Skipping "  + playerFile.getPath() + " - not a regular file");
                continue;
            }
            String name = playerFile.getName();
            if (!name.endsWith(".dat")) {
                System.out.println("Skipping "  + playerFile.getPath() + " - does not end in .dat");
                continue;
            }
            String uuidString = name.substring(0, name.length() - ".dat".length());
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException ex) {
                System.out.println("Skipping "  + playerFile.getPath() + " - not a valid uuid");
                continue;
            }
            Tag playerData;
            try {
                TagInputStream in = new TagInputStream(new GZIPInputStream(new FileInputStream(playerFile)));
                try {
                    playerData = in.readTag();
                } finally {
                    in.close();
                }
            } catch (IOException ex) {
                System.out.println("Skipping " + playerFile.getPath() + " - could not parse file");
                continue;
            }
            if (playerData.getType() != TagType.COMPOUND) {
                System.out.println("Skipping "  + playerFile.getPath() + " - contains non compound nbt tag as root");
                continue;
            }
            CompoundTag playerDataCompound = (CompoundTag) playerData;
            if (!playerDataCompound.containsKey("bukkit")) {
                System.out.println("Skipping "  + playerFile.getPath() + " - no bukkit section in player data");
                continue;
            }
            CompoundTag bukkit = playerDataCompound.getCompound("bukkit");
            if (!bukkit.containsKey("lastKnownName")) {
                System.out.println("Skipping "  + playerFile.getPath() + " - no lastKnownName in player data");
                continue;
            }
            String playerName = bukkit.getString("lastKnownName");
            if (!VALID_USERNAME.matcher(playerName).matches()) {
                System.out.println("Skipping "  + playerFile.getPath() + " - lastKnownName: " + playerName + " is not a valid player name");
                continue;
            }
            if (offline ^ isOfflineUUID(playerName, uuid)) {
                System.out.println("Skipping "  + playerFile.getPath() + " - " + (offline?"uuid is online, and we're in offline mode":"uuid is offline, and we're in online mode."));
                continue;
            }
            File targetFile = new File(playerDir, playerName + ".dat");
            if (targetFile.exists()) {
                System.out.println("Skipping "  + playerFile.getPath() + " - target file exists: " + targetFile);
                continue;
            }
            if (dryRun) {
                System.out.println("Would move " + playerFile + " to " + targetFile);
            } else {
                try {
                    TagOutputStream out = new TagOutputStream(new GZIPOutputStream(new FileOutputStream(targetFile)));
                    try {
                        out.writeTag(playerData);
                    } finally {
                        out.close();
                    }
                } catch (IOException ex) {
                    System.out.println("Skipping "  + playerFile.getPath() + " - failed to write target file");
                    targetFile.delete();
                    continue;
                }
                playerFile.delete();
            }
        }
    }
}
