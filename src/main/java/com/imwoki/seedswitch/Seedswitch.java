package com.imwoki.seedswitch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.Random;

public final class Seedswitch extends JavaPlugin implements Listener {

    private int globalIntervalSeconds = 0;
    private int globalCountdown = 0;

    private World currentOverworld;
    private World currentNether;
    private World currentEnd;

    private World pendingOverworld;
    private World pendingNether;
    private World pendingEnd;

    @Override
    public void onEnable() {
        getLogger().info("SeedSwitch enabled!");

        for (World world : getServer().getWorlds()) {
            switch (world.getEnvironment()) {
                case NORMAL -> currentOverworld = world;
                case NETHER -> currentNether = world;
                case THE_END -> currentEnd = world;
                default -> {}
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        startGlobalTicker();
    }

    @Override
    public void onDisable() {
        getLogger().info("SeedSwitch disabled.");
    }

    // Игрок зашёл на сервер — проверяем, что он в АКТУАЛЬНОМ мире, а не в удалённом старом
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World playerWorld = player.getWorld();

        boolean isInCurrentWorld = playerWorld.equals(currentOverworld)
                || playerWorld.equals(currentNether)
                || playerWorld.equals(currentEnd);

        if (!isInCurrentWorld) {
            // Его старый мир, скорее всего, был удалён — переносим в актуальный overworld
            Location loc = player.getLocation();
            int safeY = findSafeY(currentOverworld, (int) loc.getX(), (int) loc.getZ());
            player.teleport(new Location(currentOverworld, loc.getX(), safeY, loc.getZ()));
        }
    }

    // Игрок умер — respawn делаем именно в том сиде, где он умер
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Location spawnLocation = currentOverworld.getSpawnLocation();
        event.setRespawnLocation(spawnLocation);
    }

    private void startGlobalTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (globalIntervalSeconds <= 0) return;

                if (globalCountdown == 10 && pendingOverworld == null) {
                    pregenerateNextWorlds();
                }

                globalCountdown--;

                if (globalCountdown <= 0) {
                    switchAllPlayers();
                    globalCountdown = globalIntervalSeconds;
                }

                Component actionBarText = Component.text("Смена мира через: " + globalCountdown + "с")
                        .color(NamedTextColor.DARK_RED);

                for (Player p : getServer().getOnlinePlayers()) {
                    p.sendActionBar(actionBarText);

                    if (globalCountdown > 0 && globalCountdown <= 5) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7071f);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void pregenerateNextWorlds() {
        long newSeed = new Random().nextLong();
        String baseName = "seedswitch_" + System.currentTimeMillis();

        pendingOverworld = new WorldCreator(baseName + "_overworld")
                .environment(Environment.NORMAL)
                .seed(newSeed)
                .createWorld();

        pendingNether = new WorldCreator(baseName + "_nether")
                .environment(Environment.NETHER)
                .seed(newSeed)
                .createWorld();

        pendingEnd = new WorldCreator(baseName + "_end")
                .environment(Environment.THE_END)
                .seed(newSeed)
                .createWorld();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("settimer")) {
            try {
                int seconds = Integer.parseInt(args[1]);
                if (seconds < 30) {
                    sender.sendMessage("Минимальный интервал — 30 секунд.");
                    return true;
                }
                globalIntervalSeconds = seconds;
                globalCountdown = seconds;
                getServer().broadcast(Component.text(
                        "Таймер смены мира установлен: каждые " + seconds + " секунд. Удачи."));
            } catch (NumberFormatException e) {
                sender.sendMessage("Нужно указать число секунд. Пример: /seedswitch settimer 60");
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
            globalIntervalSeconds = 0;
            getServer().broadcast(Component.text("Таймер смены мира выключен."));
            return true;
        }

        sender.sendMessage("Использование: /seedswitch settimer <секунды>  или  /seedswitch off");
        return true;
    }

    private void switchAllPlayers() {
        if (pendingOverworld == null) {
            pregenerateNextWorlds();
        }

        World newOverworld = pendingOverworld;
        World newNether = pendingNether;
        World newEnd = pendingEnd;
        pendingOverworld = null;
        pendingNether = null;
        pendingEnd = null;

        if (newOverworld == null || newNether == null || newEnd == null) {
            getServer().broadcast(Component.text("Что-то пошло не так при создании миров!"));
            return;
        }

        for (Player player : getServer().getOnlinePlayers()) {
            Location oldLocation = player.getLocation();
            double x = oldLocation.getX();
            double z = oldLocation.getZ();

            World targetWorld = switch (player.getWorld().getEnvironment()) {
                case NETHER -> newNether;
                case THE_END -> newEnd;
                default -> newOverworld;
            };

            int safeY = findSafeY(targetWorld, (int) x, (int) z);
            Location newLocation = new Location(targetWorld, x, safeY, z);

            player.teleport(newLocation);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }

        deleteWorldLater(currentOverworld);
        deleteWorldLater(currentNether);
        deleteWorldLater(currentEnd);

        currentOverworld = newOverworld;
        currentNether = newNether;
        currentEnd = newEnd;
    }

    // Ищет безопасную высоту.
    private int findSafeY(World world, int x, int z) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 2;

        if (world.getEnvironment() == Environment.NETHER) {
            maxY = Math.min(maxY, 127);
        }

        for (int y = maxY; y > minY; y--) {
            Material below = world.getBlockAt(x, y - 1, z).getType();
            Material feet = world.getBlockAt(x, y, z).getType();
            Material head = world.getBlockAt(x, y + 1, z).getType();

            if (below.isSolid() && !feet.isSolid() && !head.isSolid()) {
                return y;
            }
        }

        return world.getSpawnLocation().getBlockY();
    }

    private void deleteWorldLater(World world) {
        if (world == null || !world.getName().startsWith("seedswitch_")) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                String worldName = world.getName();
                boolean unloaded = Bukkit.unloadWorld(world, false);

                if (unloaded) {
                    File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
                    deleteFolder(worldFolder);
                    getLogger().info("Deleted old world: " + worldName);
                } else {
                    getLogger().warning("Could not unload old world: " + worldName);
                }
            }
        }.runTaskLater(this, 60L); // 3 секунды задержки
    }

    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }
        folder.delete();
    }
}