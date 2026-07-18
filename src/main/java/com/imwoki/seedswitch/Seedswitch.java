package com.imwoki.seedswitch;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

public final class Seedswitch extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("SeedSwitch enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("SeedSwitch disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Команда работает только для реальных игроков, не для консоли сервера
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда только для игроков!");
            return true;
        }

        // 1. Придумываем новый случайный сид
        long newSeed = new Random().nextLong();

        // 2. Придумываем уникальное имя для нового мира
        String worldName = "seedswitch_" + System.currentTimeMillis();

        // 3. Запоминаем текущие X и Z координаты игрока
        Location oldLocation = player.getLocation();
        double x = oldLocation.getX();
        double z = oldLocation.getZ();

        player.sendMessage("Создаём новый мир, подожди немного...");

        // 4. Создаём (генерируем) новый мир с этим сидом
        WorldCreator creator = new WorldCreator(worldName);
        creator.seed(newSeed);
        World newWorld = creator.createWorld();

        if (newWorld == null) {
            player.sendMessage("Что-то пошло не так при создании мира!");
            return true;
        }

        // 5. Ищем безопасную высоту (первый твёрдый блок сверху) и телепортируем
        int safeY = newWorld.getHighestBlockYAt((int) x, (int) z) + 1;
        Location newLocation = new Location(newWorld, x, safeY, z);

        player.teleport(newLocation);
        player.sendMessage("Телепортация завершена! Новый сид: " + newSeed);

        return true;
    }
}