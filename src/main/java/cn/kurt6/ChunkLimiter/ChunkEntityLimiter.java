package cn.kurt6.ChunkLimiter;

import cn.kurt6.ChunkLimiter.bStats.Metrics;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.bukkit.Bukkit.*;

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    // 性能监控
    private final Map<String, LongSummaryStatistics> performanceStats = new ConcurrentHashMap<>();
    private boolean performanceMonitoring = false;
    private final Map<String, Long> lastPerformanceValues = new ConcurrentHashMap<>();

    private void recordPerformance(String operation, long duration) {
        if (!performanceMonitoring) return;

        performanceStats.computeIfAbsent(operation, k -> new LongSummaryStatistics())
                .accept(duration);
        lastPerformanceValues.put(operation, duration); // 记录最近值
    }

    // 配置参数
    private int defaultLimit = 100;
    private int itemLimit = 300;
    private int checkInterval = 600;
    private final Set<EntityType> ignoredTypes = ConcurrentHashMap.newKeySet();
    private final Set<Material> ignoredItems = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> customLimits = new ConcurrentHashMap<>();
    private int chunkCheckRadius = 0;
    private double chunkEntityMultiplier = 1.0;
    private double chunkItemMultiplier = 1.0;
    private double notificationRadius = 128.0;

    // 消息配置
    private String msgReloadSuccess;
    private String msgNoPermission;
    private String msgPlayerOnly;
    private String msgChunkHeader;
    private String msgMobStats;
    private String msgMobStatsLine;
    private String msgItemStatsLine;
    private String msgTotalStats;
    private String msgItemStats;
    private String msgCleanupReport;
    private String msgPreOverload;
    private String msgNotificationEnabled;
    private String msgNotificationDisabled;
    private String msgPerfPhase1;
    private String msgPerfPhase2;
    private String msgPerfTotal;
    private String msgPerfClassify;
    private String msgPerfCleanup;
    private String msgPerfHeader;
    private String msgPerfNoData;
    private String msgPerfDisabled;
    private String msgPerfReset;
    private boolean protectNamedEntities = true;
    private boolean protectLeashedEntities = true;
    private boolean protectTamedAnimals = true;
    private String msgProtectedStats;
    private String msgProtectedNamed;
    private String msgProtectedLeashed;
    private String msgProtectedTamed;
    private String msgProtectedTotal;

    // 运行时配置
    private boolean enableNotifications;
    private int notifyThreshold;
    private double thresholdRatio;
    private int notifyCooldown;
    private final Map<String, Long> lastNotifyTimes = new ConcurrentHashMap<>();
    private final NamespacedKey SPAWN_TIME_KEY = new NamespacedKey(this, "spawnTime");

    // 性能优化
    private final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(\\w+)%");
    private final Map<EntityType, Long> removalStats = new ConcurrentHashMap<>();

    // Folia 检测
    private static final boolean IS_FOLIA = checkFolia();

    private static boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private final Map<String, Map<String, String>> LANGUAGES = new HashMap<>();
    private String currentLang = "en";

    // 初始化语言数据
    private void initLanguages() {
        // 英文消息
        Map<String, String> en = new HashMap<>();
        en.put("reload-success", "&aConfiguration reloaded!");
        en.put("no-permission", "&cYou don't have permission!");
        en.put("player-only", "&cThis command can only be used in-game");
        en.put("chunk-info-header", "&6==== Current Chunk Entities &7(World: %world%) (%x%, %z%) &6====");
        en.put("mob-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        en.put("pre-overload", "&cWarning! %type% in chunk %world% (%chunkX%, %chunkZ%) nearing limit: %current%/%max%");
        en.put("mob-stats", "&6[Mobs]");
        en.put("item-stats", "&6[Items]");
        en.put("item-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        en.put("cleanup-report", "&6[Single Chunk Cleanup Report] Cleaned %mobs% mobs & %items% items in %world% (%x%,%z%)\n  &cMobs: %current_mobs% &7| &bItems: %current_items%");
        en.put("notification-enabled", "&aEntity notifications enabled");
        en.put("notification-disabled", "&cEntity notifications disabled");
        en.put("total-stats", "&6Total: &c%total_mobs% mobs &6| &b%total_items% items");
        en.put("group-cleanup-report", "&6[Batch Chunk Cleanup Report] Cleaned %mobs% mobs & %items% items in %world% (X:%x%, Z:%z%)");
        en.put("group-pre-overload", "&cWarning! %type% in chunk group %world% (Center: %centerX%, %centerZ%) nearing limit: %current%/%max%");
        // 性能统计名称
        en.put("perf-phase1", "Phase1-SingleChunks");
        en.put("perf-phase2", "Phase2-GroupChunks");
        en.put("perf-total", "Total-Cleanup");
        en.put("perf-classify", "Entity-Classification");
        en.put("perf-cleanup", "Cleanup-Enforcement");
        en.put("perf-header", "&6=== Performance Stats ===");
        en.put("perf-no-data", "&cNo performance data available");
        en.put("perf-disabled", "&cPerformance monitoring is disabled! Set performance-monitoring: true in config.yml");
        en.put("perf-reset", "&aPerformance statistics have been reset");
        // 添加保护状态相关消息
        en.put("protected-stats", "&6[Protected Entities]");
        en.put("protected-named", " &7Named: &a%count%");
        en.put("protected-leashed", " &7Leashed: &a%count%");
        en.put("protected-tamed", " &7Tamed: &a%count%");
        en.put("protected-total", " &7Total Protected: &a%count%");

        // 中文消息
        Map<String, String> zh = new HashMap<>();
        zh.put("reload-success", "&a配置已重新加载！");
        zh.put("no-permission", "&c你没有执行该命令的权限");
        zh.put("player-only", "&c该命令只能在游戏中执行");
        zh.put("chunk-info-header", "&6==== 当前区块实体统计 &7(世界: %world%) (%x%, %z%) &6====");
        zh.put("mob-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        zh.put("pre-overload", "&c警告！区块 %world% (%chunkX%, %chunkZ%) 的 %type% 数量即将超限：%current%/%max%");
        zh.put("mob-stats", "&6[生物统计]");
        zh.put("item-stats", "&6[物品统计]");
        zh.put("item-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        zh.put("cleanup-report", "&6[单区块清理报告] 在 %world% (%x%,%z%) 清理了 %mobs% 生物和 %items% 物品\n  &c生物: %current_mobs% &7| &b物品: %current_items%");
        zh.put("notification-enabled", "&a实体清理通知已启用");
        zh.put("notification-disabled", "&c实体清理通知已禁用");
        zh.put("total-stats", "&6总计: &c%total_mobs% 生物 &6| &b%total_items% 物品");
        zh.put("group-cleanup-report", "&6[组合区块清理报告] 在 %world% (X:%x%, Z:%z%) 清理了 %mobs% 生物和 %items% 物品");
        zh.put("group-pre-overload", "&c警告！区块组合 %world% (中心: %centerX%, %centerZ%) 的 %type% 数量接近上限：%current%/%max%");
        // 性能统计名称
        zh.put("perf-phase1", "阶段1-单区块处理");
        zh.put("perf-phase2", "阶段2-组合区块处理");
        zh.put("perf-total", "总清理耗时");
        zh.put("perf-classify", "实体分类");
        zh.put("perf-cleanup", "清理执行");
        zh.put("perf-header", "&6=== 性能统计 ===");
        zh.put("perf-no-data", "&c暂无性能数据");
        zh.put("perf-disabled", "&c性能监控未启用！请在config.yml中设置 performance-monitoring: true");
        zh.put("perf-reset", "&a性能统计已重置");
        // 添加保护状态相关消息
        zh.put("protected-stats", "&6[受保护实体]");
        zh.put("protected-named", " &7命名的: &a%count%");
        zh.put("protected-leashed", " &7拴住的: &a%count%");
        zh.put("protected-tamed", " &7驯服的: &a%count%");
        zh.put("protected-total", " &7受保护总计: &a%count%");

        LANGUAGES.put("en", en);
        LANGUAGES.put("zh", zh);
    }

    @Override
    public void onEnable() {
        // bStats
        int pluginId = 24723;
        Metrics metrics = new Metrics(this, pluginId);

        saveDefaultConfig();
        initLanguages(); // 初始化语言数据
        reloadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        setupCleanupTask();

        // 启动日志
        getLogger().info("ChunkLimiter v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Language: " + currentLang.toUpperCase());
        getLogger().info("Performance monitoring: " + (performanceMonitoring ? "Enabled" : "Disabled"));
        if (IS_FOLIA) {
            getLogger().info("Running on Folia - using regional scheduling");
        }
    }

    private final Object configLock = new Object();

    private void reloadConfiguration() {
        synchronized (configLock) {
            reloadConfig();
            loadSettings();
            loadMessages();
        }
    }

    private void loadSettings() {
        ConfigurationSection config = getConfig();

        // 语言设置读取
        currentLang = config.getString("settings.language", "en").toLowerCase();
        if (!LANGUAGES.containsKey(currentLang)) {
            currentLang = "en";
            getLogger().warning("Invalid language setting, defaulting to English");
        }

        // 实体限制设置
        ConfigurationSection limits = config.getConfigurationSection("entity-limits");
        defaultLimit = limits.getInt("default-limit", 100);
        itemLimit = limits.getInt("item-limit", 300);
        checkInterval = limits.getInt("check-interval-ticks", 600);
        chunkCheckRadius = Math.max(0, limits.getInt("chunk-check-radius", 0));
        // 使用 getDouble() 获取浮点数，并确保是正数
        chunkEntityMultiplier = limits.getDouble("chunk_entity_multiplier", 1.0);
        if (chunkEntityMultiplier <= 0) {
            chunkEntityMultiplier = 1.0; // 默认值，防止非正数
        }
        chunkItemMultiplier = limits.getDouble("chunk_item_multiplier", 1.0);
        if (chunkItemMultiplier <= 0) {
            chunkItemMultiplier = 1.0; // 默认值，防止非正数
        }

        loadEnumSet(ignoredTypes, limits.getStringList("ignored-types"), EntityType.class);
        loadEnumSet(ignoredItems, limits.getStringList("ignored-items"), Material.class);

        ConfigurationSection custom = limits.getConfigurationSection("custom-limits");
        if (custom != null) {
            custom.getKeys(false).forEach(k ->
                    customLimits.put(k.toUpperCase(), custom.getInt(k)));
        }

        // 通知设置
        ConfigurationSection settings = config.getConfigurationSection("settings");
        enableNotifications = settings.getBoolean("enable-notifications", true);
        notifyThreshold = Math.min(100, Math.max(0, settings.getInt("notify-threshold", 90)));
        thresholdRatio = notifyThreshold / 100.0;
        notifyCooldown = settings.getInt("notify-cooldown", 10);
        notificationRadius = Math.max(0, Math.min(1000, settings.getDouble("notification-radius", 128.0)));
        performanceMonitoring = settings.getBoolean("performance-monitoring", false);

        // 保护设置
        ConfigurationSection protection = config.getConfigurationSection("protection");
        if (protection != null) {
            protectNamedEntities = protection.getBoolean("protect-named-entities", true);
            protectLeashedEntities = protection.getBoolean("protect-leashed-entities", true);
            protectTamedAnimals = protection.getBoolean("protect-tamed-animals", true);
        }
    }
    private String msgGroupCleanupReport;
    private String msgGroupPreOverload;
    private void loadMessages() {
        Map<String, String> messages = LANGUAGES.get(currentLang);

        msgReloadSuccess = parseMessage(messages.getOrDefault("reload-success", ""));
        msgNoPermission = parseMessage(messages.getOrDefault("no-permission", ""));
        msgPlayerOnly = parseMessage(messages.getOrDefault("player-only", ""));
        msgChunkHeader = parseMessage(messages.getOrDefault("chunk-info-header", ""));
        msgMobStats = parseMessage(messages.getOrDefault("mob-stats", ""));
        msgMobStatsLine = parseMessage(messages.getOrDefault("mob-stats-line", ""));
        msgItemStatsLine = parseMessage(messages.getOrDefault("item-stats-line", ""));
        msgTotalStats = parseMessage(messages.getOrDefault("total-stats", ""));
        msgItemStats = parseMessage(messages.getOrDefault("item-stats", ""));
        msgCleanupReport = parseMessage(messages.getOrDefault("cleanup-report", ""));
        msgPreOverload = parseMessage(messages.getOrDefault("pre-overload", ""));
        msgNotificationEnabled = parseMessage(messages.getOrDefault("notification-enabled", ""));
        msgNotificationDisabled = parseMessage(messages.getOrDefault("notification-disabled", ""));
        msgGroupCleanupReport = parseMessage(messages.getOrDefault("group-cleanup-report", ""));
        msgGroupPreOverload = parseMessage(messages.getOrDefault("group-pre-overload", ""));
        // 加载性能统计消息
        msgPerfPhase1 = parseMessage(messages.getOrDefault("perf-phase1", ""));
        msgPerfPhase2 = parseMessage(messages.getOrDefault("perf-phase2", ""));
        msgPerfTotal = parseMessage(messages.getOrDefault("perf-total", ""));
        msgPerfClassify = parseMessage(messages.getOrDefault("perf-classify", ""));
        msgPerfCleanup = parseMessage(messages.getOrDefault("perf-cleanup", ""));
        msgPerfHeader = parseMessage(messages.getOrDefault("perf-header", ""));
        msgPerfNoData = parseMessage(messages.getOrDefault("perf-no-data", ""));
        msgPerfDisabled = parseMessage(messages.getOrDefault("perf-disabled", ""));
        msgPerfReset = parseMessage(messages.getOrDefault("perf-reset", ""));
        // 加载保护状态消息
        msgProtectedStats = parseMessage(messages.getOrDefault("protected-stats", ""));
        msgProtectedNamed = parseMessage(messages.getOrDefault("protected-named", ""));
        msgProtectedLeashed = parseMessage(messages.getOrDefault("protected-leashed", ""));
        msgProtectedTamed = parseMessage(messages.getOrDefault("protected-tamed", ""));
        msgProtectedTotal = parseMessage(messages.getOrDefault("protected-total", ""));
    }

    private String parseMessage(String raw) {
        return raw != null ? ChatColor.translateAlternateColorCodes('&', raw) : "";
    }

    private <T extends Enum<T>> void loadEnumSet(Set<T> set, List<String> values, Class<T> enumClass) {
        set.clear();
        values.forEach(str -> {
            try {
                set.add(Enum.valueOf(enumClass, str.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid configuration item: " + str);
            }
        });
    }

    private void setupCleanupTask() {
        Runnable task = this::processAllChunks;
        if (IS_FOLIA) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, t -> task.run(), checkInterval, checkInterval);
        } else {
            getServer().getScheduler().scheduleSyncRepeatingTask(this, task, checkInterval, checkInterval);
        }
    }

    private void processAllChunks() {
        long totalStart = System.currentTimeMillis();

        if (IS_FOLIA) {
            getGlobalRegionScheduler().run(this, task -> {
                Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();

                // 第一阶段计时
                long phase1Start = System.currentTimeMillis();

                // 第一阶段：处理单个区块
                for (World world : getWorlds()) {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        getRegionScheduler().run(this, world, chunk.getX(), chunk.getZ(),
                                t -> processSingleChunk(chunk));
                    }
                }

                recordPerformance("Phase1-SingleChunks", System.currentTimeMillis() - phase1Start);

                // 延迟执行第二阶段，确保第一阶段完成
                if (chunkCheckRadius > 0) {
                    getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
                    // 第二阶段：处理组合区块

                        long phase2Start = System.currentTimeMillis();

                        getOnlinePlayers().forEach(player -> {
                            getRegionScheduler().execute(this, player.getLocation(),
                                    () -> processPlayerChunkGroup(player, globalProcessed));
                        });

                        recordPerformance("Phase2-GroupChunks", System.currentTimeMillis() - phase2Start);
                        recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart - 1000);
                    }, 20L); // 延迟1秒
                } else {
                    recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
                }
            });
        } else {
            // Paper处理逻辑
            Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();

            // 第一阶段
            long phase1Start = System.currentTimeMillis();

            // 第一阶段：处理所有单个区块
            for (World world : getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    processSingleChunk(chunk);
                }
            }

            recordPerformance("Phase1-SingleChunks", System.currentTimeMillis() - phase1Start);

            // 第二阶段：处理玩家周围的组合区块
            if (chunkCheckRadius > 0) {
                getServer().getScheduler().runTaskLater(this, () -> {
                    long phase2Start = System.currentTimeMillis();

                    getOnlinePlayers().forEach(player ->
                            processPlayerChunkGroup(player, globalProcessed));

                    recordPerformance("Phase2-GroupChunks", System.currentTimeMillis() - phase2Start);
                    recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart - 1000);
                }, 20L);
            } else {
                recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            }
        }
    }

    private void processPlayerChunkGroup(Player player, Set<Chunk> globalProcessed) {
        if (chunkCheckRadius <= 0) return;

        Location loc = player.getLocation();
        Chunk center = loc.getChunk();
        World world = center.getWorld();
        List<Chunk> chunkGroup = new ArrayList<>();

        // 收集区块组
        for (int x = -chunkCheckRadius; x <= chunkCheckRadius; x++) {
            for (int z = -chunkCheckRadius; z <= chunkCheckRadius; z++) {
                Chunk chunk = world.getChunkAt(center.getX() + x, center.getZ() + z);
                if (chunk.isLoaded() && !globalProcessed.contains(chunk)) {
                    chunkGroup.add(chunk);
                    globalProcessed.add(chunk);
                }
            }
        }

        if (!chunkGroup.isEmpty()) {
            processChunkGroupWithWarning(chunkGroup);
        }
    }

    private void processChunkGroupWithWarning(List<Chunk> chunks) {
        try {
            // 合并所有实体
            Map<EntityType, List<Entity>> mobs = new HashMap<>();
            List<Entity> allItems = new ArrayList<>(); // 物品作为整体处理

            for (Chunk chunk : chunks) {
                if (!chunk.isLoaded()) continue;

                Arrays.stream(chunk.getEntities())
                        .filter(e -> !(e instanceof Player))
                        .forEach(entity -> {
                            EntityCategory category = classifyEntity(entity);
                            if (category == EntityCategory.MOB) {
                                mobs.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
                            } else if (category == EntityCategory.ITEM) {
                                Item item = (Item) entity;
                                if (!ignoredItems.contains(item.getItemStack().getType())) {
                                    allItems.add(entity);
                                }
                            }
                        });
            }

            // 清理生物
            AtomicInteger removedMobs = new AtomicInteger(0);
            mobs.forEach((type, list) -> {
                if (list.isEmpty()) return;

                int singleChunkLimit = getLimitFor(type);
                int groupLimit = (int) Math.ceil(singleChunkLimit * chunkEntityMultiplier);

                // 预警检查
                if (list.size() >= groupLimit * thresholdRatio) {
                    sendGroupWarning(chunks, type.name(), list.size(), groupLimit);
                }

                int removed = enforceLimit(list, groupLimit);
                if (removed > 0) {
                    removedMobs.addAndGet(removed);
                }
            });

            // 清理物品（整体处理）
            int removedItems = 0;
            int groupItemLimit = (int) Math.ceil(itemLimit * chunkItemMultiplier);

            // 物品预警检查
            if (allItems.size() >= groupItemLimit * thresholdRatio) {
                sendGroupWarning(chunks, "Items", allItems.size(), groupItemLimit);
            }

            removedItems = enforceLimit(allItems, groupItemLimit);

            // 发送报告给附近玩家
            if (removedMobs.get() + removedItems > 0) {
                sendGroupCleanupReportToNearby(chunks, removedMobs.get(), removedItems);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "处理区块组时出错", e);
        }
    }

    private void sendGroupWarning(List<Chunk> chunks, String typeName, int current, int limit) {
        if (chunks.isEmpty()) return;

        Chunk firstChunk = chunks.get(0);
        World world = firstChunk.getWorld();

        // 计算中心位置（区块坐标）
        int centerChunkX = chunks.stream().mapToInt(Chunk::getX).sum() / chunks.size();
        int centerChunkZ = chunks.stream().mapToInt(Chunk::getZ).sum() / chunks.size();

        // 生成组预警的唯一键
        String groupKey = typeName + ":group:" + world.getName() + ":" + centerChunkX + ":" + centerChunkZ;

        // 检查冷却时间
        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(groupKey, 0L) > notifyCooldown * 1000L) {
            // 转换为方块坐标用于定位
            double centerX = centerChunkX * 16 + 8;
            double centerZ = centerChunkZ * 16 + 8;
            Location center = new Location(world, centerX, world.getHighestBlockYAt((int)centerX, (int)centerZ), centerZ);

            // 使用占位符替换
            Map<String, String> params = new HashMap<>();
            params.put("type", typeName);
            params.put("world", world.getName());
            params.put("centerX", String.valueOf(centerChunkX));
            params.put("centerZ", String.valueOf(centerChunkZ));
            params.put("current", String.valueOf(current));
            params.put("max", String.valueOf(limit));

            String message = replacePlaceholders(msgGroupPreOverload, params);

            // 发送给附近玩家
            sendMessageToNearbyPlayers(center, message, 128);

            // 更新最后通知时间
            lastNotifyTimes.put(groupKey, System.currentTimeMillis());

            // 清理过期的通知时间记录
            if (lastNotifyTimes.size() > 1000) {
                lastNotifyTimes.keySet().removeIf(key ->
                        System.currentTimeMillis() - lastNotifyTimes.get(key) > notifyCooldown * 2000L
                );
            }
        }
    }

    private void sendGroupCleanupReportToNearby(List<Chunk> chunks, int removedMobs, int removedItems) {
        if (removedMobs + removedItems == 0 || chunks.isEmpty()) return;

        Chunk firstChunk = chunks.get(0);
        World world = firstChunk.getWorld();

        // 计算中心区块坐标
        int centerChunkX = chunks.stream().mapToInt(Chunk::getX).sum() / chunks.size();
        int centerChunkZ = chunks.stream().mapToInt(Chunk::getZ).sum() / chunks.size();

        // 计算范围
        int minX = chunks.stream().mapToInt(Chunk::getX).min().orElse(0);
        int maxX = chunks.stream().mapToInt(Chunk::getX).max().orElse(0);
        int minZ = chunks.stream().mapToInt(Chunk::getZ).min().orElse(0);
        int maxZ = chunks.stream().mapToInt(Chunk::getZ).max().orElse(0);

        // 转换为方块坐标用于定位
        double centerX = centerChunkX * 16 + 8;
        double centerZ = centerChunkZ * 16 + 8;
        Location center = new Location(world, centerX, world.getHighestBlockYAt((int)centerX, (int)centerZ), centerZ);

        // 构建消息
        Map<String, String> params = new HashMap<>();
        params.put("mobs", String.valueOf(removedMobs));
        params.put("items", String.valueOf(removedItems));
        // 如果是单个区块，显示具体坐标；如果是多个区块，显示范围
        if (chunks.size() == 1) {
            params.put("x", String.valueOf(centerChunkX));
            params.put("z", String.valueOf(centerChunkZ));
        } else {
            params.put("x", minX + "~" + maxX);
            params.put("z", minZ + "~" + maxZ);
        }
        params.put("world", world.getName());

        String message = replacePlaceholders(msgGroupCleanupReport, params);

        // 发送给附近玩家
        sendMessageToNearbyPlayers(center, message, 128);

        // 控制台日志
        getLogger().info(ChatColor.stripColor(message));
    }

    // 通用的附近玩家消息发送方法
    private void sendMessageToNearbyPlayers(Location center, String message, double defaultRadius) {
        if (!enableNotifications) return;

        double radius = notificationRadius > 0 ? notificationRadius : defaultRadius;

        center.getWorld().getPlayers().stream()
                .filter(p -> {
                    Location pLoc = p.getLocation();
                    // 只计算X和Z轴的距离
                    double dx = pLoc.getX() - center.getX();
                    double dz = pLoc.getZ() - center.getZ();
                    return Math.sqrt(dx * dx + dz * dz) <= radius;
                })
                .forEach(p -> p.sendMessage(message));
    }

    private void processSingleChunk(Chunk chunk) {
        try {
            if (!chunk.isLoaded()) return;

            // 实体分类计时
            long classifyStart = System.currentTimeMillis();
            // 实体分类处理
            Map<EntityCategory, List<Entity>> entities = Arrays.stream(chunk.getEntities())
                    .filter(e -> !(e instanceof Player))
                    .collect(Collectors.groupingBy(this::classifyEntity));
            recordPerformance("Entity-Classification", System.currentTimeMillis() - classifyStart);

            // 清理计时
            long cleanupStart = System.currentTimeMillis();
            // 执行清理逻辑
            int removedMobs = processMobs(entities.getOrDefault(EntityCategory.MOB, Collections.emptyList()));
            int removedItems = processItems(entities.getOrDefault(EntityCategory.ITEM, Collections.emptyList()));
            recordPerformance("Cleanup-Enforcement", System.currentTimeMillis() - cleanupStart);

            // 发送报告
            sendCleanupReport(chunk, removedMobs, removedItems);
            checkChunkStatus(chunk);

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "处理区块时出错: " + chunk, e);
        }
    }

    private int getLimitFor(EntityType type) {
        // 检查是否为忽略类型
        if (ignoredTypes.contains(type)) {
            return Integer.MAX_VALUE; // 返回极大值表示无限制
        }
        // 优先使用自定义限制
        return customLimits.getOrDefault(type.name(), defaultLimit);
    }

    private enum EntityCategory { MOB, ITEM, OTHER }
    private EntityCategory classifyEntity(Entity e) {
        // 提前检查最常见的情况
        if (e instanceof Item) {
            return EntityCategory.ITEM;
        }

        // 避免重复检查 instanceof
        if (!(e instanceof LivingEntity)) {
            return EntityCategory.OTHER;
        }

        // 最后检查忽略类型
        return ignoredTypes.contains(e.getType()) ?
                EntityCategory.OTHER : EntityCategory.MOB;
    }

    private int processMobs(List<Entity> mobs) {
        int totalRemoved = 0;
        Map<EntityType, List<Entity>> grouped = new HashMap<>();

        // 分组逻辑
        for (Entity e : mobs) {
            if (!e.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                e.getPersistentDataContainer().set(SPAWN_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis());
            }
            grouped.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add(e);
        }

        // 处理每个类型
        for (Map.Entry<EntityType, List<Entity>> entry : grouped.entrySet()) {
            int limit = customLimits.getOrDefault(entry.getKey().name(), defaultLimit);
            int removed = enforceLimit(entry.getValue(), limit);
            removalStats.merge(entry.getKey(), (long)removed, Long::sum);
            totalRemoved += removed;
        }
        return totalRemoved;
    }

    private int processItems(List<Entity> items) {
        List<Entity> validItems = items.stream()
                .filter(item -> {
                    if (ignoredItems.contains(((Item) item).getItemStack().getType())) {
                        return false;
                    }
                    // 添加时间标记（如果不存在）
                    if (!item.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                        item.getPersistentDataContainer().set(SPAWN_TIME_KEY,
                                PersistentDataType.LONG, System.currentTimeMillis());
                    }
                    return true;
                })
                .collect(Collectors.toList());

        return enforceLimit(validItems, itemLimit);
    }

    private int enforceLimit(List<Entity> entities, int limit) {
        if (entities.size() <= limit) return 0;

        // 过滤出可以被移除的实体
        List<Entity> removableEntities = entities.stream()
                .filter(this::canRemoveEntity)
                .collect(Collectors.toList());

        // 如果可移除的实体数量已经在限制内，不需要移除任何实体
        if (removableEntities.size() + (entities.size() - removableEntities.size()) <= limit) {
            return 0;
        }

        // 使用优先队列维护最早生成的可移除实体
        PriorityQueue<Entity> oldestQueue = new PriorityQueue<>(Comparator.comparingLong(e ->
                e.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY, PersistentDataType.LONG, Long.MAX_VALUE)
        ));
        oldestQueue.addAll(removableEntities);

        // 计算需要移除的数量（总数 - 限制 - 受保护的数量）
        int protectedCount = entities.size() - removableEntities.size();
        int toRemove = Math.max(0, entities.size() - limit);
        toRemove = Math.min(toRemove, removableEntities.size());

        List<Location> removalLocations = new ArrayList<>();

        for (int i = 0; i < toRemove; i++) {
            Entity e = oldestQueue.poll();
            if (e != null) {
                removalLocations.add(e.getLocation());
                e.remove();
            }
        }

        // 批量生成粒子效果（减少网络包）
        if (!removalLocations.isEmpty() && removalLocations.size() < 50) { // 限制粒子数量
            World world = removalLocations.get(0).getWorld();
            removalLocations.forEach(loc ->
                    world.spawnParticle(Particle.EXPLOSION_NORMAL, loc, 5));
        }

        return toRemove;
    }

    private boolean canRemoveEntity(Entity entity) {
        // 检查命名的实体
        if (protectNamedEntities && entity.getCustomName() != null) {
            return false;
        }

        // 检查被拴住的实体
        if (protectLeashedEntities && entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            if (living.isLeashed()) {
                return false;
            }
        }

        // 检查驯服的动物
        if (protectTamedAnimals && entity instanceof Tameable) {
            Tameable tameable = (Tameable) entity;
            if (tameable.isTamed()) {
                return false;
            }
        }

        return true;
    }

    private void sendCleanupReport(Chunk chunk, int removedMobs, int removedItems) {
        if (removedMobs + removedItems == 0) return;

        // 获取当前区块的实时统计
        ChunkStats stats = collectChunkStats(chunk);
        int currentMobs = stats.mobCounts.values().stream().mapToInt(Integer::intValue).sum();
        int currentItems = stats.itemCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, String> params = new HashMap<>();
        params.put("mobs", String.valueOf(removedMobs));
        params.put("items", String.valueOf(removedItems));
        params.put("x", String.valueOf(chunk.getX()));
        params.put("z", String.valueOf(chunk.getZ()));
        params.put("world", chunk.getWorld().getName());
        params.put("current_mobs", String.valueOf(currentMobs));
        params.put("current_items", String.valueOf(currentItems));

        if (enableNotifications) {
            String message = replacePlaceholders(msgCleanupReport, params);

            // 发送到控制台（去除颜色代码）
            getLogger().info(ChatColor.stripColor(message));

            // 计算区块中心位置
            Location chunkCenter = new Location(
                    chunk.getWorld(),
                    chunk.getX() * 16 + 8,
                    chunk.getWorld().getHighestBlockYAt(chunk.getX() * 16 + 8, chunk.getZ() * 16 + 8),
                    chunk.getZ() * 16 + 8
            );

            // 发送给附近玩家
            sendMessageToNearbyPlayers(chunkCenter, message, 128);
        }
    }

    private String replacePlaceholders(String template, Map<String, String> replacements) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = replacements.getOrDefault(key, matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // 获取区块内有效实体的统计信息
    private ChunkStats collectChunkStats(Chunk chunk) {
        ChunkStats stats = new ChunkStats();

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) continue;

            if (entity instanceof LivingEntity && !ignoredTypes.contains(entity.getType())) {
                stats.mobCounts.merge(entity.getType(), 1, Integer::sum);
            } else if (entity instanceof Item) {
                ItemStack stack = ((Item) entity).getItemStack();
                if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                    stats.itemCounts.merge(stack.getType(), 1, Integer::sum);
                }
            }
        }
        return stats;
    }

    private static class ChunkStats {
        final Map<EntityType, Integer> mobCounts = new HashMap<>();
        final Map<Material, Integer> itemCounts = new HashMap<>();
    }

    private void checkChunkStatus(Chunk chunk) {
        // 检查区块有效性
        if (chunk == null || !chunk.isLoaded()) return;
        ChunkStats stats = collectChunkStats(chunk);

        // 处理生物预警
        stats.mobCounts.forEach((type, count) -> {
            int limit = customLimits.getOrDefault(type.name(), defaultLimit);
            if (count >= limit * thresholdRatio) {
                sendTypeWarning(chunk, type.name(), count, limit);
            }
        });

        // 处理物品预警
        int totalItems = stats.itemCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalItems >= itemLimit * thresholdRatio) {
            sendTypeWarning(chunk, "Items", totalItems, itemLimit);
        }
    }

    private void sendTypeWarning(Chunk chunk, String typeName, int current, int limit) {
        World world = chunk.getWorld();
        String worldName = world.getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        String chunkKey = typeName + ":" + worldName + ":" + chunkX + ":" + chunkZ;

        if (!chunk.isLoaded()) {
            lastNotifyTimes.remove(chunkKey);
            return;
        }

        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(chunkKey, 0L) > notifyCooldown * 1000L) {
            String message = msgPreOverload
                    .replace("%type%", typeName)
                    .replace("%current%", String.valueOf(current))
                    .replace("%max%", String.valueOf(limit))
                    .replace("%chunkX%", String.valueOf(chunkX))
                    .replace("%chunkZ%", String.valueOf(chunkZ))
                    .replace("%world%", worldName);

            // 计算区块中心位置
            Location chunkCenter = new Location(
                    world,
                    chunkX * 16 + 8,
                    world.getHighestBlockYAt(chunkX * 16 + 8, chunkZ * 16 + 8),
                    chunkZ * 16 + 8
            );

            // 发送给附近玩家
            sendMessageToNearbyPlayers(chunkCenter, message, 128);

            lastNotifyTimes.put(chunkKey, System.currentTimeMillis());
            if (lastNotifyTimes.size() > 1000) {
                lastNotifyTimes.keySet().removeIf(key ->
                        System.currentTimeMillis() - lastNotifyTimes.get(key) > notifyCooldown * 2000L
                );
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("chunklimit")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("chunklimiter.reload")) {
                    sender.sendMessage(msgNoPermission);
                    return true;
                }
                reloadConfiguration();
                sender.sendMessage(msgReloadSuccess);
                return true;

            case "stats":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(msgPlayerOnly);
                    return true;
                }
                if (!sender.hasPermission("chunklimiter.stats")) {
                    sender.sendMessage(msgNoPermission);
                    return true;
                }
                showChunkStats((Player) sender);
                return true;

            case "notify":
                handleNotifyCommand(sender, args);
                return true;

            case "performance":
                if (!sender.hasPermission("chunklimiter.performance")) {
                    sender.sendMessage(msgNoPermission);
                    return true;
                }

                // 重置功能
                if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
                    performanceStats.clear();
                    lastPerformanceValues.clear();
                    sender.sendMessage(msgPerfReset);
                    return true;
                }

                if (!performanceMonitoring) {
                    sender.sendMessage(msgPerfDisabled);
                    return true;
                }

                showPerformanceStats(sender);
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    // 显示性能统计
    private void showPerformanceStats(CommandSender sender) {
        if (performanceStats.isEmpty()) {
            sender.sendMessage(msgPerfNoData);
            return;
        }

        sender.sendMessage(msgPerfHeader);

        // 按指定顺序显示，使用本地化的名称
        Map<String, String> statDisplayNames = new LinkedHashMap<>();
        statDisplayNames.put("Phase1-SingleChunks", msgPerfPhase1);
        statDisplayNames.put("Phase2-GroupChunks", msgPerfPhase2);
        statDisplayNames.put("Total-Cleanup", msgPerfTotal);
        statDisplayNames.put("Entity-Classification", msgPerfClassify);
        statDisplayNames.put("Cleanup-Enforcement", msgPerfCleanup);

        for (Map.Entry<String, String> entry : statDisplayNames.entrySet()) {
            String statKey = entry.getKey();
            String displayName = entry.getValue();

            LongSummaryStatistics stats = performanceStats.get(statKey);
            Long lastValue = lastPerformanceValues.get(statKey);

            if (stats != null && stats.getCount() > 0) {
                String message = String.format(
                        "&7%s: &a%dms &7(%s: &a%.0fms&7)",
                        displayName,
                        lastValue != null ? lastValue : 0,
                        currentLang.equals("zh") ? "平均" : "avg",
                        stats.getAverage()
                );
                sender.sendMessage(parseMessage(message));
            }
        }
    }

    // 定期清理缓存
    private void setupMaintenanceTask() {
        Runnable maintenance = () -> {
            // 清理过期的通知记录
            long expireTime = System.currentTimeMillis() - (notifyCooldown * 2000L);
            lastNotifyTimes.entrySet().removeIf(entry -> entry.getValue() < expireTime);

            // 清理性能统计（保留最近1小时的数据）
            if (performanceStats.size() > 100) {
                performanceStats.clear();
            }

            if (removalStats.size() > 1000) {
                removalStats.clear();
            }
        };

        // 每5分钟执行一次
        if (IS_FOLIA) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, t -> maintenance.run(), 6000L, 6000L);
        } else {
            getServer().getScheduler().runTaskTimerAsynchronously(this, maintenance, 6000L, 6000L);
        }
    }

    private void sendHelp(CommandSender sender) {
        String langPrefix = currentLang.equals("zh") ? "&6用法：" : "&6Usage:";
        String[] helpMessages = currentLang.equals("zh") ?
                new String[] {
                        "&a/chunklimiter reload &7- 重载配置",
                        "&a/chunklimiter notify [on|off] &7- 切换通知状态",
                        "&a/chunklimiter stats &7- 查看当前区块统计",
                        "&a/chunklimiter performance &7- 查看性能监控",
                        "&a/chunklimiter performance reset &7- 重置性能监控"
                } :
                new String[] {
                        "&a/chunklimiter reload &7- Reload config",
                        "&a/chunklimiter notify [on|off] &7- Toggle notifications",
                        "&a/chunklimiter stats &7- Show chunk stats",
                        "&a/chunklimiter performance &7- View performance monitoring",
                        "&a/chunklimiter performance reset &7- Reset performance monitoring"
                };

        sender.sendMessage(parseMessage(langPrefix));
        for (String msg : helpMessages) {
            sender.sendMessage(parseMessage(msg));
        }
    }

    // 存储保护统计信息
    private static class ProtectionStats {
        int namedCount = 0;
        int leashedCount = 0;
        int tamedCount = 0;
        int totalProtected = 0;

        void addEntity(Entity entity, boolean protectNamed, boolean protectLeashed, boolean protectTamed) {
            boolean isProtected = false;

            if (protectNamed && entity.getCustomName() != null) {
                namedCount++;
                isProtected = true;
            }

            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                if (protectLeashed && living.isLeashed()) {
                    leashedCount++;
                    isProtected = true;
                }
            }

            if (entity instanceof Tameable) {
                Tameable tameable = (Tameable) entity;
                if (protectTamed && tameable.isTamed()) {
                    tamedCount++;
                    isProtected = true;
                }
            }

            if (isProtected) {
                totalProtected++;
            }
        }
    }

    private void showChunkStats(Player player) {
        Chunk chunk = player.getLocation().getChunk();

        // 显示当前区块统计
        showSingleChunkStats(player, chunk);

        // 如果启用了区块组，显示组合统计
        if (chunkCheckRadius > 0) {
            showGroupChunkStats(player);
        }
    }

    // 组合区块统计显示
    private void showGroupChunkStats(Player player) {
        Location loc = player.getLocation();
        Chunk center = loc.getChunk();
        World world = center.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        // 收集区块组
        List<Chunk> chunkGroup = new ArrayList<>();
        for (int x = -chunkCheckRadius; x <= chunkCheckRadius; x++) {
            for (int z = -chunkCheckRadius; z <= chunkCheckRadius; z++) {
                Chunk chunk = world.getChunkAt(center.getX() + x, center.getZ() + z);
                if (chunk.isLoaded()) {
                    chunkGroup.add(chunk);
                }
            }
        }

        // 统计所有实体（包括被忽略的）
        Map<EntityType, Integer> totalMobs = new HashMap<>();
        Map<EntityType, Integer> ignoredMobCounts = new HashMap<>();
        int totalItemCount = 0;
        int ignoredItemCount = 0;

        for (Chunk chunk : chunkGroup) {
            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Player) continue;

                if (entity instanceof LivingEntity) {
                    EntityType type = entity.getType();
                    totalMobs.merge(type, 1, Integer::sum);
                    if (ignoredTypes.contains(type)) {
                        ignoredMobCounts.merge(type, 1, Integer::sum);
                    }
                    // 添加保护统计
                    protectionStats.addEntity(entity, protectNamedEntities, protectLeashedEntities, protectTamedAnimals);
                } else if (entity instanceof Item) {
                    Material type = ((Item) entity).getItemStack().getType();
                    if (ignoredItems.contains(type)) {
                        ignoredItemCount++;
                    } else {
                        totalItemCount++;
                    }
                }
            }
        }

        // 显示组合统计
        String header = currentLang.equals("zh") ?
                String.format("&6==== 区块组合统计 (半径: %d, 共%d个区块) ====", chunkCheckRadius, chunkGroup.size()) :
                String.format("&6==== Group Chunk Stats(Radius: %d, %d chunks) ====", chunkCheckRadius, chunkGroup.size());

        player.sendMessage(parseMessage(header));

        // 显示生物统计和限制
        if (!totalMobs.isEmpty()) {
            player.sendMessage(parseMessage(msgMobStats));

            // 先显示非忽略的生物
            totalMobs.entrySet().stream()
                    .filter(entry -> !ignoredTypes.contains(entry.getKey()))
                    .forEach(entry -> {
                        EntityType type = entry.getKey();
                        int count = entry.getValue();
                        int singleLimit = getLimitFor(type);
                        int groupLimit = (int) Math.ceil(singleLimit * chunkEntityMultiplier);

                        Map<String, String> params = new HashMap<>();
                        params.put("type", type.name());
                        params.put("count", String.valueOf(count));
                        params.put("limit", String.valueOf(groupLimit));

                        player.sendMessage(replacePlaceholders(msgMobStatsLine, params));
                    });

            // 再显示被忽略的生物
            ignoredMobCounts.forEach((type, count) -> {
                Map<String, String> params = new HashMap<>();
                params.put("type", type.name() + " (ignored)");
                params.put("count", String.valueOf(count));
                params.put("limit", currentLang.equals("zh") ? "无限制" : "Unlimited");

                player.sendMessage(replacePlaceholders(msgMobStatsLine, params));
            });
        }

        // 显示物品统计和限制
        player.sendMessage(parseMessage(msgItemStats));
        if (totalItemCount > 0) {
            int groupItemLimit = (int) Math.ceil(itemLimit * chunkItemMultiplier);

            Map<String, String> params = new HashMap<>();
            params.put("type", "Items");
            params.put("count", String.valueOf(totalItemCount));
            params.put("limit", String.valueOf(groupItemLimit));

            player.sendMessage(replacePlaceholders(msgItemStatsLine, params));
        }

        if (ignoredItemCount > 0) {
            Map<String, String> params = new HashMap<>();
            params.put("type", "Ignored Items");
            params.put("count", String.valueOf(ignoredItemCount));
            params.put("limit", currentLang.equals("zh") ? "无限制" : "Unlimited");

            player.sendMessage(replacePlaceholders(msgItemStatsLine, params));
        }

        // 显示保护统计
        if (protectionStats.totalProtected > 0) {
            player.sendMessage(msgProtectedStats);

            if (protectionStats.namedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.namedCount));
                player.sendMessage(replacePlaceholders(msgProtectedNamed, params));
            }

            if (protectionStats.leashedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.leashedCount));
                player.sendMessage(replacePlaceholders(msgProtectedLeashed, params));
            }

            if (protectionStats.tamedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.tamedCount));
                player.sendMessage(replacePlaceholders(msgProtectedTamed, params));
            }

            Map<String, String> params = new HashMap<>();
            params.put("count", String.valueOf(protectionStats.totalProtected));
            player.sendMessage(replacePlaceholders(msgProtectedTotal, params));
        }

        // 显示总计
        int totalMobCount = totalMobs.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, String> totalParams = new HashMap<>();
        totalParams.put("total_mobs", String.valueOf(totalMobCount));
        totalParams.put("total_items", String.valueOf(totalItemCount + ignoredItemCount));
        player.sendMessage(replacePlaceholders(msgTotalStats, totalParams));
    }

    // 单个区块统计
    private void showSingleChunkStats(Player player, Chunk chunk) {
        World world = chunk.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        // 收集所有生物（包括被忽略的）
        Map<EntityType, Long> allMobCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof LivingEntity && !(e instanceof Player))
                .peek(e -> protectionStats.addEntity(e, protectNamedEntities, protectLeashedEntities, protectTamedAnimals))
                .collect(Collectors.groupingBy(
                        Entity::getType,
                        Collectors.counting()
                ));

        // 收集所有物品（包括被忽略的）
        Map<Material, Long> allItemCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof Item)
                .map(e -> ((Item) e).getItemStack().getType())
                .filter(type -> type != Material.AIR)
                .collect(Collectors.groupingBy(
                        material -> material,
                        Collectors.counting()
                ));

        // 构建基础参数
        Map<String, String> baseParams = new HashMap<>();
        baseParams.put("x", String.valueOf(chunk.getX()));
        baseParams.put("z", String.valueOf(chunk.getZ()));
        baseParams.put("world", world.getName());

        // 发送区块头信息
        player.sendMessage(replacePlaceholders(msgChunkHeader, baseParams));

        // 生物统计部分
        if (!allMobCounts.isEmpty()) {
            player.sendMessage(replacePlaceholders(msgMobStats, baseParams));

            allMobCounts.forEach((type, count) -> {
                boolean isIgnored = ignoredTypes.contains(type);
                int limit = isIgnored ? -1 : customLimits.getOrDefault(type.name(), defaultLimit);

                Map<String, String> params = new HashMap<>(baseParams);
                params.put("type", type.name());
                params.put("count", String.valueOf(count));
                params.put("limit", isIgnored
                        ? (currentLang.equals("zh") ? "无限制" : "Unlimited")
                        : String.valueOf(limit));

                player.sendMessage(replacePlaceholders(msgMobStatsLine, params));
            });
        }

        // 物品统计部分
        if (!allItemCounts.isEmpty()) {
            player.sendMessage(replacePlaceholders(msgItemStats, baseParams));

            allItemCounts.forEach((material, count) -> {
                boolean isIgnored = ignoredItems.contains(material);
                int limit = isIgnored ? -1 : itemLimit;

                Map<String, String> params = new HashMap<>(baseParams);
                params.put("type", material.name());
                params.put("count", String.valueOf(count));
                params.put("limit", isIgnored
                        ? (currentLang.equals("zh") ? "无限制" : "Unlimited")
                        : String.valueOf(limit));

                player.sendMessage(replacePlaceholders(msgItemStatsLine, params));
            });
        }

        // 显示保护统计
        if (protectionStats.totalProtected > 0) {
            player.sendMessage(msgProtectedStats);

            if (protectionStats.namedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.namedCount));
                player.sendMessage(replacePlaceholders(msgProtectedNamed, params));
            }

            if (protectionStats.leashedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.leashedCount));
                player.sendMessage(replacePlaceholders(msgProtectedLeashed, params));
            }

            if (protectionStats.tamedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.tamedCount));
                player.sendMessage(replacePlaceholders(msgProtectedTamed, params));
            }

            Map<String, String> params = new HashMap<>();
            params.put("count", String.valueOf(protectionStats.totalProtected));
            player.sendMessage(replacePlaceholders(msgProtectedTotal, params));
        }

        // 总数统计
        long totalMobs = allMobCounts.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        long totalItems = allItemCounts.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        Map<String, String> totalParams = new HashMap<>(baseParams);
        totalParams.put("total_mobs", String.valueOf(totalMobs));
        totalParams.put("total_items", String.valueOf(totalItems));
        player.sendMessage(replacePlaceholders(msgTotalStats, totalParams));
    }

    private void handleNotifyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(msgPlayerOnly);
            return;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("chunklimiter.notify")) {
            player.sendMessage(msgNoPermission);
            return;
        }

        if (args.length == 1) {
            enableNotifications = !enableNotifications;
            player.sendMessage(enableNotifications ? msgNotificationEnabled : msgNotificationDisabled);
        } else {
            boolean newState = args[1].equalsIgnoreCase("on");
            enableNotifications = newState;
            player.sendMessage(newState ? msgNotificationEnabled : msgNotificationDisabled);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = Arrays.asList("reload", "stats", "notify", "help", "performance");
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "notify":
                    return Arrays.asList("on", "off");
                case "performance":
                    return Arrays.asList("reset");
            }
        }

        return Collections.emptyList();
    }

}