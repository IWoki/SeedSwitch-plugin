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
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.io.File;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class Seedswitch extends JavaPlugin implements Listener {

    private int globalIntervalSeconds = 0;
    private int globalCountdown = 0;
    private File stateFile;

    // Текущие загруженные миры: измерение -> мир. Не обязательно все три сразу!
    private final Map<Environment, World> currentWorlds = new EnumMap<>(Environment.class);
    private long currentSeed;
    private String currentBaseName;

    // Подготовленные заранее миры для следующей смены (тоже не обязательно все три)
    private final Map<Environment, World> pendingWorlds = new EnumMap<>(Environment.class);
    private long pendingSeed;
    private String pendingBaseName;

    @Override
    public void onEnable() {
        getLogger().info("SeedSwitch enabled!");

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        stateFile = new File(getDataFolder(), "state.yml");

        loadOrCreateCurrentWorlds();

        getServer().getPluginManager().registerEvents(this, this);
        startGlobalTicker();
    }

    // Загружает миры из сохранённого состояния, либо создаёт первую пару миров, если это самый первый запуск
    private void loadOrCreateCurrentWorlds() {
        if (stateFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(stateFile);

            currentSeed = config.getLong("seed");
            currentBaseName = config.getString("baseName");
            long savedTime = config.getLong("time", 0L);
            List<String> dimensionNames = config.getStringList("dimensions");

            for (String dimName : dimensionNames) {
                Environment env = Environment.valueOf(dimName);
                World world = createWorldFor(env, currentBaseName, currentSeed);
                world.setTime(savedTime);
                currentWorlds.put(env, world);
            }

            getLogger().info("Restored previous seed from state.yml: " + currentSeed);
        } else {
            // Самый первый запуск за всю историю — создаём свою первую пару миров,
            // дефолтный мир сервера (world/world_nether/world_the_end) больше не используем
            currentSeed = new Random().nextLong();
            currentBaseName = "seedswitch_" + System.currentTimeMillis();

            World overworld = createWorldFor(Environment.NORMAL, currentBaseName, currentSeed);
            currentWorlds.put(Environment.NORMAL, overworld);

            saveCurrentState();
            getLogger().info("First ever launch — generated initial world with seed: " + currentSeed);
        }
    }

    // Сохраняет текущий сид/имя/список измерений в state.yml
    private void saveCurrentState() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("seed", currentSeed);
        config.set("baseName", currentBaseName);

        // Сохраняем время из overworld, иначе из любого другого измерения
        World anyWorld = currentWorlds.containsKey(Environment.NORMAL)
                ? currentWorlds.get(Environment.NORMAL)
                : currentWorlds.values().iterator().next();
        config.set("time", anyWorld.getTime());

        List<String> dimensionNames = new ArrayList<>();
        for (Environment env : currentWorlds.keySet()) {
            dimensionNames.add(env.name());
        }
        config.set("dimensions", dimensionNames);

        try {
            config.save(stateFile);
        } catch (IOException e) {
            getLogger().warning("Could not save state.yml: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("SeedSwitch disabled.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!currentWorlds.containsValue(player.getWorld())) {
            World overworld = currentWorlds.get(Environment.NORMAL);
            Location loc = player.getLocation();
            int safeY = findSafeY(overworld, (int) loc.getX(), (int) loc.getZ());
            player.teleport(new Location(overworld, loc.getX(), safeY, loc.getZ()));
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        World overworld = currentWorlds.get(Environment.NORMAL);
        if (overworld == null) {
            getLogger().warning("Overworld missing in currentWorlds during respawn! Falling back to any available world.");
            overworld = currentWorlds.values().stream().findFirst().orElse(null);
        }
        if (overworld != null) {
            event.setRespawnLocation(overworld.getSpawnLocation());
        }
    }

    // Игрок зашёл в портал — если нужного измерения ещё нет, генерируем его прямо сейчас
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        Environment fromEnv = event.getFrom().getWorld().getEnvironment();
        Environment targetEnv = resolveTargetEnvironment(fromEnv, event.getCause());

        World targetWorld = currentWorlds.get(targetEnv);
        if (targetWorld == null) {
            targetWorld = createWorldFor(targetEnv, currentBaseName, currentSeed);
            long currentTime = currentWorlds.values().iterator().next().getTime();
            targetWorld.setTime(currentTime);
            currentWorlds.put(targetEnv, targetWorld);
            saveCurrentState();
            getLogger().info("Lazily generated dimension: " + targetEnv);
        }

        Location destination;

        if (targetEnv == Environment.THE_END) {
            // Вход в Энд — всегда на фиксированную платформу (как в ваниле), а не по X/Z игрока
            destination = new Location(targetWorld, 100.5, 50, 0.5);
        } else if (fromEnv == Environment.THE_END) {
            // Выход ИЗ Энда — всегда на точку спавна мира (как в ваниле), а не по X/Z в Энде
            destination = targetWorld.getSpawnLocation();
        } else {
            // Обычный портал Незера — переносим по тем же X/Z координатам
            double x = event.getFrom().getX();
            double z = event.getFrom().getZ();
            int safeY = findSafeY(targetWorld, (int) x, (int) z);
            destination = new Location(targetWorld, x, safeY, z);
        }

        event.setTo(destination);
    }

    // Определяет, в какое измерение ведёт портал, из которого сейчас входит игрок
    private Environment resolveTargetEnvironment(Environment from, PlayerTeleportEvent.TeleportCause cause) {
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return from == Environment.NETHER ? Environment.NORMAL : Environment.NETHER;
        }
        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return from == Environment.THE_END ? Environment.NORMAL : Environment.THE_END;
        }
        return Environment.NORMAL;
    }

    private void startGlobalTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (globalIntervalSeconds <= 0) return;

                if (globalCountdown == 7 && pendingWorlds.isEmpty()) {
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

    // Генерирует ТОЛЬКО те измерения, где реально есть онлайн-игроки прямо сейчас
    private void pregenerateNextWorlds() {
        pendingSeed = new Random().nextLong();
        pendingBaseName = "seedswitch_" + System.currentTimeMillis();
        pendingWorlds.clear();

        Set<Environment> occupied = new HashSet<>();
        for (Player p : getServer().getOnlinePlayers()) {
            occupied.add(p.getWorld().getEnvironment());
        }

        // Overworld генерируем ВСЕГДА — он нужен как минимум для респавна после смерти
        occupied.add(Environment.NORMAL);

        for (Environment env : occupied) {
            World world = createWorldFor(env, pendingBaseName, pendingSeed);
            pendingWorlds.put(env, world);
        }
    }

    // Создаёт мир нужного измерения с нужным именем и сидом
    private World createWorldFor(String baseNameOrNull, Environment env, long seed) {
        return createWorldFor(env, baseNameOrNull, seed);
    }

    private World createWorldFor(Environment env, String baseName, long seed) {
        String suffix = switch (env) {
            case NETHER -> "_nether";
            case THE_END -> "_end";
            default -> "_overworld";
        };
        return new WorldCreator(baseName + suffix)
                .environment(env)
                .seed(seed)
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
        if (pendingWorlds.isEmpty()) {
            pregenerateNextWorlds();
        }

        // Запоминаем текущее время (день/ночь), чтобы перенести его в новые миры
        long currentTime = currentWorlds.values().iterator().hasNext()
                ? currentWorlds.values().iterator().next().getTime()
                : 0L;

        Map<Environment, World> newWorlds = new EnumMap<>(pendingWorlds);
        long newSeed = pendingSeed;
        String newBaseName = pendingBaseName;
        pendingWorlds.clear();

        // Выставляем сохранённое время всем подготовленным мирам
        for (World world : newWorlds.values()) {
            world.setTime(currentTime);
        }

        for (Player player : getServer().getOnlinePlayers()) {
            Environment env = player.getWorld().getEnvironment();

            World targetWorld = newWorlds.computeIfAbsent(env, e -> {
                World w = createWorldFor(e, newBaseName, newSeed);
                w.setTime(currentTime); // и на случай "аварийной" генерации тоже
                return w;
            });

            Location oldLocation = player.getLocation();
            double x = oldLocation.getX();
            double z = oldLocation.getZ();

            int safeY = findSafeY(targetWorld, (int) x, (int) z);
            Location newLocation = new Location(targetWorld, x, safeY, z);

            player.teleport(newLocation);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }

        getServer().broadcast(Component.text("Мир сменился! Новый сид: " + newSeed));

        for (World oldWorld : currentWorlds.values()) {
            deleteWorldLater(oldWorld);
        }

        currentWorlds.clear();
        currentWorlds.putAll(newWorlds);
        currentSeed = newSeed;
        currentBaseName = newBaseName;
        saveCurrentState();
    }

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
        if (world == null || world.getName() == null || !world.getName().startsWith("seedswitch_")) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                String worldName = world.getName();
                boolean unloaded = Bukkit.unloadWorld(world, false);

                if (unloaded) {
                    File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
                    attemptDeleteFolder(worldFolder, worldName, 0);
                } else {
                    getLogger().warning("Could not unload old world: " + worldName);
                }
            }
        }.runTaskLater(this, 20L);
    }

    private void attemptDeleteFolder(File folder, String worldName, int attempt) {
        boolean success = deleteFolder(folder);

        if (success) {
            getLogger().info("Deleted old world: " + worldName);
            return;
        }

        if (attempt >= 5) {
            getLogger().warning("Failed to delete world after 5 attempts: " + worldName);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                attemptDeleteFolder(folder, worldName, attempt + 1);
            }
        }.runTaskLater(this, 40L);
    }

    private boolean deleteFolder(File folder) {
        File[] files = folder.listFiles();
        boolean allDeleted = true;

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    allDeleted &= deleteFolder(file);
                } else {
                    if (!file.delete()) {
                        allDeleted = false;
                    }
                }
            }
        }

        if (allDeleted) {
            allDeleted = folder.delete();
        }

        return allDeleted;
    }
}