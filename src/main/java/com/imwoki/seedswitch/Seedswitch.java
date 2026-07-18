package com.imwoki.seedswitch;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;

import java.io.File;
import java.util.Random;

public final class Seedswitch extends JavaPlugin {

    private int globalIntervalSeconds = 0;
    private int globalCountdown = 0;

    // Мир, в котором игроки находятся ПРЯМО СЕЙЧАС
    private World currentWorld;

    // Мир, который мы готовим ЗАРАНЕЕ для следующей смены
    private World pendingNextWorld;

    @Override
    public void onEnable() {
        getLogger().info("SeedSwitch enabled!");
        // Мир по умолчанию (тот, что создаётся при первом запуске сервера) считаем стартовым
        currentWorld = getServer().getWorlds().get(0);
        startGlobalTicker();
    }

    @Override
    public void onDisable() {
        getLogger().info("SeedSwitch disabled.");
    }

    private void startGlobalTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (globalIntervalSeconds <= 0) return;

                if (globalCountdown == 5 && pendingNextWorld == null) {
                    pregenerateNextWorld();
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

    // Генерирует новый мир ЗАРАНЕЕ и складывает его "про запас"
    private void pregenerateNextWorld() {
        long newSeed = new Random().nextLong();
        String worldName = "seedswitch_" + System.currentTimeMillis();

        WorldCreator creator = new WorldCreator(worldName);
        creator.seed(newSeed);
        pendingNextWorld = creator.createWorld();
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

        sender.sendMessage("Использование: /seedswitch settimer <секунды>  или  /seedswitch off для отключения таймера");
        return true;
    }

    private void switchAllPlayers() {
        // На всякий случай: если по какой-то причине мир не успел подготовиться заранее —
        // генерируем прямо сейчас (это и есть тот самый редкий случай лага, но он подстраховка)
        if (pendingNextWorld == null) {
            pregenerateNextWorld();
        }

        World newWorld = pendingNextWorld;
        pendingNextWorld = null;

        if (newWorld == null) {
            getServer().broadcast(Component.text("Что-то пошло не так при создании мира!"));
            return;
        }

        for (Player player : getServer().getOnlinePlayers()) {
            Location oldLocation = player.getLocation();
            double x = oldLocation.getX();
            double z = oldLocation.getZ();

            int safeY = newWorld.getHighestBlockYAt((int) x, (int) z) + 1;
            Location newLocation = new Location(newWorld, x, safeY, z);

            player.teleport(newLocation);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }

        // Старый мир больше не нужен — выгружаем и удаляем с диска
        World worldToDelete = currentWorld;
        currentWorld = newWorld;

        if (worldToDelete != null && worldToDelete.getName().startsWith("seedswitch_")) {
            deleteWorldLater(worldToDelete);
        }
    }

    // Удаляем мир через 1 секунду (даём серверу время убедиться, что все точно телепортировались)
    private void deleteWorldLater(World world) {
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
        }.runTaskLater(this, 20L);
    }

    // Обычное рекурсивное удаление папки со всем содержимым
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