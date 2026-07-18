package com.imwoki.seedswitch;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

public final class Seedswitch extends JavaPlugin {

    // Один общий интервал на весь сервер (в секундах). 0 = таймер выключен.
    private int globalIntervalSeconds = 0;
    // Сколько секунд осталось до следующей смены мира
    private int globalCountdown = 0;

    @Override
    public void onEnable() {
        getLogger().info("SeedSwitch enabled!");
        startGlobalTicker();
    }

    @Override
    public void onDisable() {
        getLogger().info("SeedSwitch disabled.");
    }

    // Тикает раз в секунду, пока сервер работает
    private void startGlobalTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (globalIntervalSeconds <= 0) return; // таймер выключен — ничего не делаем

                globalCountdown--;

                if (globalCountdown <= 0) {
                    switchAllPlayers();
                    globalCountdown = globalIntervalSeconds; // сбрасываем отсчёт заново
                }

                // Показываем обратный отсчёт всем игроками одновременно
                Component actionBarText = Component.text("Смена мира через: " + globalCountdown + "с");
                for (Player p : getServer().getOnlinePlayers()) {
                    p.sendActionBar(actionBarText);
                }
            }
        }.runTaskTimer(this, 0L, 20L); // 20 тиков = 1 секунда
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("settimer")) {
            try {
                int seconds = Integer.parseInt(args[1]);
                if (seconds < 10) {
                    sender.sendMessage("Минимальный интервал — 10 секунд.");
                    return true;
                }
                globalIntervalSeconds = seconds;
                globalCountdown = seconds;
                getServer().broadcast(Component.text(
                        "Таймер смены мира установлен: каждые " + seconds + " Удачи."));
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

    // Создаёт ОДИН новый мир и переносит туда ВСЕХ онлайн-игроков
    private void switchAllPlayers() {
        long newSeed = new Random().nextLong();
        String worldName = "seedswitch_" + System.currentTimeMillis();

        WorldCreator creator = new WorldCreator(worldName);
        creator.seed(newSeed);
        World newWorld = creator.createWorld();

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
        }
        
    }
}