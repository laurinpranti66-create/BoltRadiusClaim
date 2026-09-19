package de.playground.boltradiusclaim;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.protection.BlockProtection;

/**
 * BoltRadiusClaim
 * ----------------
 * Sperrt (claimt) mit einem einzigen Befehl alle Bolt-sperrbaren Blöcke
 * (Truhen, Türen, Fässer, Shulker-Boxen, Öfen, Braustände, ...) in einem
 * Radius um den ausführenden Spieler.
 *
 * Hintergrund: Bolt legt Protections nur an, wenn ein Spieler einen Block
 * ganz normal über den Bukkit-BlockPlaceEvent platziert (oder manuell
 * /bolt lock nutzt). WorldEdit's //copy und //paste schreiben Blockdaten
 * jedoch direkt in die Chunk-Daten, ohne dieses Event auszulösen - dadurch
 * bekommen per Copy/Paste eingefügte Truhen, Türen usw. NIE automatisch
 * eine Bolt-Protection. Dieses Plugin schließt genau diese Lücke: nach
 * einem Paste einmal /boltclaim <radius> ausführen, und alles Sperrbare
 * im Bereich wird nachträglich für den ausführenden Spieler gesperrt.
 *
 * Bereits bestehende Protections werden NIEMALS überschrieben oder
 * angefasst - der Befehl claimt ausschließlich noch ungeschützte Blöcke.
 */
public final class BoltRadiusClaimPlugin extends JavaPlugin implements CommandExecutor {

    private BoltAPI bolt;
    private int maxRadius;
    private String protectionType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.maxRadius = getConfig().getInt("max-radius", 25);
        this.protectionType = getConfig().getString("protection-type", "private");

        this.bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (this.bolt == null) {
            getLogger().severe("Bolt wurde nicht gefunden (BoltAPI-Service nicht registriert)! " +
                    "Dieses Plugin benötigt Bolt als Abhängigkeit und wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var command = getCommand("boltclaim");
        if (command != null) {
            command.setExecutor(this);
        }

        getLogger().info("BoltRadiusClaim aktiviert (max-radius=" + maxRadius + ", type=" + protectionType + ").");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von Spielern ausgeführt werden.");
            return true;
        }

        if (!player.hasPermission("playground.boltclaim")) {
            player.sendMessage(ChatColor.RED + "Dazu hast du keine Berechtigung.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Nutzung: " + ChatColor.GRAY + "/boltclaim <radius>");
            return true;
        }

        final int radius;
        try {
            radius = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Ungültiger Radius. Nutzung: " + ChatColor.GRAY + "/boltclaim <radius>");
            return true;
        }

        if (radius <= 0) {
            player.sendMessage(ChatColor.RED + "Der Radius muss größer als 0 sein.");
            return true;
        }

        if (radius > maxRadius) {
            player.sendMessage(ChatColor.RED + "Der maximale Radius ist " + ChatColor.YELLOW + maxRadius
                    + ChatColor.RED + " Blöcke (siehe config.yml).");
            return true;
        }

        claimRadius(player, radius);
        return true;
    }

    private void claimRadius(Player player, int radius) {
        final World world = player.getWorld();
        final Location center = player.getLocation();

        final int centerX = center.getBlockX();
        final int centerY = center.getBlockY();
        final int centerZ = center.getBlockZ();

        final int minY = Math.max(world.getMinHeight(), centerY - radius);
        final int maxY = Math.min(world.getMaxHeight() - 1, centerY + radius);

        int locked = 0;
        int alreadyProtected = 0;
        int notLockable = 0;
        int skippedUnloadedChunks = 0;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    skippedUnloadedChunks++;
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    final Block block = world.getBlockAt(x, y, z);

                    if (!bolt.isProtectable(block)) {
                        notLockable++;
                        continue;
                    }
                    if (bolt.isProtected(block)) {
                        alreadyProtected++;
                        continue;
                    }

                    final BlockProtection protection = bolt.createProtection(block, player.getUniqueId(), protectionType);
                    bolt.saveProtection(protection);
                    locked++;
                }
            }
        }

        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Bolt Radius-Claim" + ChatColor.GRAY + " (Radius " + radius + "):");
        player.sendMessage(ChatColor.GREEN + "  ✔ " + ChatColor.YELLOW + locked + ChatColor.GREEN + " neue Blöcke gesperrt.");
        if (alreadyProtected > 0) {
            player.sendMessage(ChatColor.GRAY + "  • " + ChatColor.YELLOW + alreadyProtected
                    + ChatColor.GRAY + " Blöcke waren bereits geschützt (übersprungen).");
        }
        if (skippedUnloadedChunks > 0) {
            player.sendMessage(ChatColor.GRAY + "  • " + ChatColor.YELLOW + skippedUnloadedChunks
                    + ChatColor.GRAY + " Chunk-Spalten waren nicht geladen und wurden übersprungen.");
        }
    }
}
