package cn.kurt6.ChunkLimiter;

import cn.kurt6.ChunkLimiter.metrics.Metrics;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    private final NamespacedKey SPAWN_TIME_KEY = new NamespacedKey(this, "spawnTime");
    private int defaultLimit;
    private int itemLimit;
    private int checkInterval;
    private Map<String, Integer> customLimits = new HashMap<>();
    private List<EntityType> ignoredTypes = new ArrayList<>();
    private List<Material> ignoredItems = new ArrayList<>();
    private boolean isFolia;

    @Override
    public void onEnable() {
        // bStats
        int pluginId = 24723;
        Metrics metrics = new Metrics(this, pluginId);

        saveDefaultConfig();
        reloadConfig();
        getServer().getPluginManager().registerEvents(this, this);

        isFolia = checkIsFolia();
        startCleanupTask();
    }

    private boolean checkIsFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        ConfigurationSection limits = getConfig().getConfigurationSection("entity-limits");
        defaultLimit = limits.getInt("default-limit", 50);
        itemLimit = limits.getInt("item-limit", 100);
        checkInterval = limits.getInt("check-interval-ticks", 6000);

        loadIgnoredTypes(limits.getStringList("ignored-types"));
        loadIgnoredItems(limits.getStringList("ignored-items"));
        loadCustomLimits(limits.getConfigurationSection("custom-limits"));
    }

    private void loadIgnoredTypes(List<String> types) {
        ignoredTypes.clear();
        for (String typeName : types) {
            try {
                ignoredTypes.add(EntityType.valueOf(typeName.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("无效的生物类型: " + typeName);
            }
        }
    }

    private void loadIgnoredItems(List<String> items) {
        ignoredItems.clear();
        for (String itemName : items) {
            try {
                ignoredItems.add(Material.valueOf(itemName.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("无效的物品类型: " + itemName);
            }
        }
    }

    private void loadCustomLimits(ConfigurationSection custom) {
        customLimits.clear();
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                customLimits.put(key.toUpperCase(), custom.getInt(key));
            }
        }
    }

    private void startCleanupTask() {
        if (isFolia) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(
                    this,
                    task -> scheduleWorldCleanups(),
                    checkInterval,
                    checkInterval
            );
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    runCleanupForAllWorlds();
                }
            }.runTaskTimer(this, checkInterval, checkInterval);
        }
    }

    private void scheduleWorldCleanups() {
        for (World world : getServer().getWorlds()) {
            if (isFolia) {
                Chunk[] loadedChunks = world.getLoadedChunks();
                for (Chunk chunk : loadedChunks) {
                    if (Bukkit.isOwnedByCurrentRegion(world, chunk.getX(), chunk.getZ())) {
                        cleanupExcessEntities(chunk);
                    } else {
                        scheduleChunkCleanup(world, chunk.getX(), chunk.getZ());
                    }
                }
            } else {
                runWorldCleanup(world);
            }
        }
    }

    private void scheduleChunkCleanup(World world, int x, int z) {
        Bukkit.getRegionScheduler().run(
                this,
                world,
                x,
                z,
                task -> {
                    Chunk chunk = world.getChunkAt(x, z);
                    if (chunk.isLoaded()) {
                        cleanupExcessEntities(chunk);
                    }
                }
        );
    }

    private void runCleanupForAllWorlds() {
        for (World world : getServer().getWorlds()) {
            runWorldCleanup(world);
        }
    }

    private void runWorldCleanup(World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.isLoaded()) {
                cleanupExcessEntities(chunk);
            }
        }
    }

    private void cleanupExcessEntities(Chunk chunk) {
        Map<String, List<Entity>> entitiesByType = new HashMap<>();
        List<Entity> items = new ArrayList<>();

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Creature && !isIgnoredType(entity.getType())) {
                String type = entity.getType().name();
                entitiesByType.computeIfAbsent(type, k -> new ArrayList<>()).add(entity);
            } else if (entity instanceof Item) {
                Item item = (Item) entity;
                if (!isIgnoredItem(item.getItemStack().getType())) {
                    items.add(entity);
                }
            }
        }

        entitiesByType.forEach((type, entities) -> {
            int limit = getEntityLimit(type);
            if (entities.size() > limit) {
                sortAndRemove(entities, limit);
            }
        });

        if (items.size() > itemLimit) {
            sortAndRemove(items, itemLimit);
        }
    }

    private void sortAndRemove(List<Entity> entities, int limit) {
        entities.sort((e1, e2) -> {
            long t1 = e1.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY, PersistentDataType.LONG, 0L);
            long t2 = e2.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY, PersistentDataType.LONG, 0L);
            return Long.compare(t2, t1); // 降序排序
        });
        for (int i = 0; i < entities.size() - limit; i++) {
            entities.get(i).remove();
        }
    }

    private int countEntitiesOfType(Chunk chunk, String entityType) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity.getType().name().equals(entityType) && !isIgnoredType(entity.getType())) {
                count++;
            }
        }
        return count;
    }

    private int countItemsInChunk(Chunk chunk) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item) {
                Item item = (Item) entity;
                if (!isIgnoredItem(item.getItemStack().getType())) {
                    count++;
                }
            }
        }
        return count;
    }

    private int getEntityLimit(String entityType) {
        return customLimits.getOrDefault(entityType.toUpperCase(), defaultLimit);
    }

    private boolean isIgnoredType(EntityType type) {
        return ignoredTypes.contains(type);
    }

    private boolean isIgnoredItem(Material material) {
        return ignoredItems.contains(material);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("entitylimiterreload")) {
            if (!sender.hasPermission("chunklimiter.reload")) {
                sender.sendMessage(ChatColor.RED + "没有执行该命令的权限");
                return true;
            }
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "配置已重载！");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("chunkinfo")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "该命令只能在游戏内由玩家执行");
                return true;
            }
            if (!sender.hasPermission("chunklimiter.info")) {
                sender.sendMessage(ChatColor.RED + "没有查看区块信息的权限！");
                return true;
            }

            Player player = (Player) sender;
            Chunk chunk = player.getLocation().getChunk();

            Map<EntityType, Integer> entities = new HashMap<>();
            Map<Material, Integer> items = new HashMap<>();
            int otherCount = 0;

            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Player) continue; // 排除玩家

                if (entity instanceof LivingEntity) {
                    if (entity instanceof ArmorStand) continue;
                    EntityType type = entity.getType();
                    entities.put(type, entities.getOrDefault(type, 0) + 1);
                }
                else if (entity instanceof Item) {
                    Material type = ((Item) entity).getItemStack().getType();
                    items.put(type, items.getOrDefault(type, 0) + ((Item) entity).getItemStack().getAmount());
                }
                else {
                    otherCount++;
                }
            }

            player.sendMessage(ChatColor.GOLD + "===== 区块信息统计 ["+chunk.getX()+", "+chunk.getZ()+"] =====");

            player.sendMessage(ChatColor.RED + "【生物实体】");
            entities.forEach((type, count) ->
                    player.sendMessage(ChatColor.YELLOW + type.name() + ": " + ChatColor.GREEN + count)
            );

            player.sendMessage(ChatColor.RED + "\n【掉落物品】");
            items.forEach((mat, count) ->
                    player.sendMessage(ChatColor.AQUA + mat.name() + ": " + ChatColor.GREEN + count)
            );

            player.sendMessage(ChatColor.RED + "\n【其他实体】: " + ChatColor.GREEN + otherCount);

            return true;
        }
        return false;
    }

}