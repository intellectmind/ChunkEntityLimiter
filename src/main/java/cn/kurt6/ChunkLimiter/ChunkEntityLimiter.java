package cn.kurt6.ChunkLimiter;

import cn.kurt6.ChunkLimiter.bStats.Metrics;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    // 性能监控
    private final Map<String, PerformanceStats> performanceStats = new ConcurrentHashMap<>();
    private boolean performanceMonitoring = false;
    private final Map<String, Long> lastPerformanceValues = new ConcurrentHashMap<>();

    // 统计缓存
    private final Map<String, CachedChunkStats> chunkStatsCache = new ConcurrentHashMap<>();

    /**
     * 缓存的区块统计
     */
    private static class CachedChunkStats {
        final ChunkStats stats;
        final long timestamp;
        final int entityCount;

        CachedChunkStats(ChunkStats stats, int entityCount) {
            this.stats = stats;
            this.entityCount = entityCount;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid(int currentCount) {
            return System.currentTimeMillis() - timestamp < 5000
                    && currentCount == entityCount;
        }
    }

    /**
     * 记录性能指标
     */
    private void recordPerformance(String operation, long duration) {
        if (!performanceMonitoring) return;

        try {
            performanceStats.computeIfAbsent(operation, k -> new PerformanceStats())
                    .addValue(duration);
        } catch (Exception e) {
            getLogger().fine("Error recording performance for " + operation + ": " + e.getMessage());
        }
    }

    /**
     * 性能统计数据类
     */
    private static class PerformanceStats {
        private volatile long totalTime = 0;
        private volatile long count = 0;
        private volatile long lastValue = 0;
        private final Object lock = new Object();
        private volatile long minValue = Long.MAX_VALUE;
        private volatile long maxValue = Long.MIN_VALUE;
        private final int maxSamples = 1000;
        private final long[] samples = new long[maxSamples];
        private volatile int currentIndex = 0;
        private volatile boolean isFull = false;

        public void addValue(long value) {
            synchronized (lock) {
                lastValue = value;
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);

                if (isFull) {
                    totalTime -= samples[currentIndex];
                } else {
                    count++;
                    if (count >= maxSamples) {
                        isFull = true;
                        count = maxSamples;
                    }
                }

                samples[currentIndex] = value;
                totalTime += value;
                currentIndex = (currentIndex + 1) % maxSamples;
            }
        }

        public double getAverage() {
            synchronized (lock) {
                return count > 0 ? (double) totalTime / count : 0;
            }
        }

        public long getLastValue() {
            synchronized (lock) {
                return lastValue;
            }
        }

        public long getCount() {
            synchronized (lock) {
                return count;
            }
        }
    }

    // 配置参数
    private int defaultLimit = 100;
    private int itemLimit = 300;
    private int checkInterval = 600;
    private final Set<EntityType> ignoredTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Material> ignoredItems = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> customLimits = new ConcurrentHashMap<>();
    private volatile int chunkCheckRadius = 0;
    private volatile double chunkEntityMultiplier = 1.0;
    private volatile double chunkItemMultiplier = 1.0;
    private volatile double notificationRadius = 128.0;
    private volatile boolean debugMode = false;

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
    private String msgNotificationAll;
    private String msgNotificationStaff;
    private String msgNotificationNone;
    private String msgUnknownNotificationStatus;
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
    private boolean protectEquippedEntities = true;
    private boolean protectBossEntities = true;
    private String msgProtectedEquipped;
    private String msgProtectedBoss;
    private String msgGroupCleanupReport;
    private String msgGroupPreOverload;

    // 运行时配置
    private volatile String notifications;
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

    /**
     * 检测是否运行在 Folia 环境
     */
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

    /**
     * 初始化语言数据
     */
    private void initLanguages() {
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
        en.put("notification-all", "&aEntity notifications will send to all players");
        en.put("notification-staff", "&6Entity notifications will send to staff");
        en.put("notification-none", "&cEntity notifications will now be muted");
        en.put("unknown-notification-status", "&cUnknown notification status! Acceptable values (all, staff, none)");
        en.put("total-stats", "&6Total: &c%total_mobs% mobs &6| &b%total_items% items");
        en.put("group-cleanup-report", "&6[Batch Chunk Cleanup Report] Cleaned %mobs% mobs & %items% items in %world% (X:%x%, Z:%z%)");
        en.put("group-pre-overload", "&cWarning! %type% in chunk group %world% (Center: %centerX%, %centerZ%) nearing limit: %current%/%max%");
        en.put("perf-phase1", "Phase1-SingleChunks");
        en.put("perf-phase2", "Phase2-GroupChunks");
        en.put("perf-total", "Total-Cleanup");
        en.put("perf-classify", "Entity-Classification");
        en.put("perf-cleanup", "Cleanup-Enforcement");
        en.put("perf-header", "&6=== Performance Stats ===");
        en.put("perf-no-data", "&cNo performance data available");
        en.put("perf-disabled", "&cPerformance monitoring is disabled! Set performance-monitoring: true in config.yml");
        en.put("perf-reset", "&aPerformance statistics have been reset");
        en.put("protected-stats", "&6[Protected Entities]");
        en.put("protected-named", " &7Named: &a%count%");
        en.put("protected-leashed", " &7Leashed: &a%count%");
        en.put("protected-tamed", " &7Tamed: &a%count%");
        en.put("protected-total", " &7Total Protected: &a%count%");
        en.put("protected-equipped", " &7Equipped: &a%count%");
        en.put("protected-boss", " &7Boss: &a%count%");

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
        en.put("notification-all", "&a实体通知将发送给所有玩家");
        en.put("notification-staff", "&6实体通知将发送给员工");
        en.put("notification-none", "&c实体通知现已静音。");
        en.put("unknown-notification-status", "&c通知状态未知！可接受的值（全部、员工、无）");
        zh.put("total-stats", "&6总计: &c%total_mobs% 生物 &6| &b%total_items% 物品");
        zh.put("group-cleanup-report", "&6[组合区块清理报告] 在 %world% (X:%x%, Z:%z%) 清理了 %mobs% 生物和 %items% 物品");
        zh.put("group-pre-overload", "&c警告！区块组合 %world% (中心: %centerX%, %centerZ%) 的 %type% 数量接近上限：%current%/%max%");
        zh.put("perf-phase1", "阶段1-单区块处理");
        zh.put("perf-phase2", "阶段2-组合区块处理");
        zh.put("perf-total", "总清理耗时");
        zh.put("perf-classify", "实体分类");
        zh.put("perf-cleanup", "清理执行");
        zh.put("perf-header", "&6=== 性能统计 ===");
        zh.put("perf-no-data", "&c暂无性能数据");
        zh.put("perf-disabled", "&c性能监控未启用！请在config.yml中设置 performance-monitoring: true");
        zh.put("perf-reset", "&a性能统计已重置");
        zh.put("protected-stats", "&6[受保护实体]");
        zh.put("protected-named", " &7命名的: &a%count%");
        zh.put("protected-leashed", " &7拴住的: &a%count%");
        zh.put("protected-tamed", " &7驯服的: &a%count%");
        zh.put("protected-total", " &7受保护总计: &a%count%");
        zh.put("protected-equipped", " &7有装备的: &a%count%");
        zh.put("protected-boss", " &7Boss实体: &a%count%");

        LANGUAGES.put("en", en);
        LANGUAGES.put("zh", zh);
    }

    @Override
    public void onEnable() {
        // bStats
        int pluginId = 24723;
        Metrics metrics = new Metrics(this, pluginId);

        saveDefaultConfig();
        initLanguages();
        reloadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        setupCleanupTask();
        setupMaintenanceTask();

        getLogger().info("ChunkLimiter v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Language: " + currentLang.toUpperCase());
        getLogger().info("Performance monitoring: " + (performanceMonitoring ? "Enabled" : "Disabled"));
        getLogger().info("Debug mode: " + (debugMode ? "Enabled" : "Disabled"));
        if (IS_FOLIA) {
            getLogger().info("Running on Folia - using regional scheduling");
        }
    }

    private final Object configLock = new Object();
    private volatile boolean reloadInProgress = false;

    /**
     * 重载配置文件
     */
    private void reloadConfiguration() {
        if (reloadInProgress) {
            getLogger().warning("Configuration reload already in progress");
            return;
        }

        reloadInProgress = true;
        try {
            synchronized (configLock) {
                reloadConfig();

                try {
                    loadSettings();
                    loadMessages();
                    getLogger().info("Configuration reloaded successfully");
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Failed to reload config, keeping old settings", e);
                }
            }
        } finally {
            reloadInProgress = false;
        }
    }

    /**
     * 加载配置设置
     */
    private void loadSettings() {
        ConfigurationSection config = getConfig();

        currentLang = config.getString("settings.language", "en").toLowerCase();
        if (!LANGUAGES.containsKey(currentLang)) {
            currentLang = "en";
            getLogger().warning("Invalid language setting, defaulting to English");
        }

        ConfigurationSection limits = config.getConfigurationSection("entity-limits");
        defaultLimit = limits.getInt("default-limit", 100);
        itemLimit = limits.getInt("item-limit", 300);
        checkInterval = limits.getInt("check-interval-ticks", 600);
        chunkCheckRadius = Math.max(0, limits.getInt("chunk-check-radius", 0));

        chunkEntityMultiplier = limits.getDouble("chunk_entity_multiplier", 1.0);
        if (chunkEntityMultiplier <= 0) {
            chunkEntityMultiplier = 1.0;
        }

        chunkItemMultiplier = limits.getDouble("chunk_item_multiplier", 1.0);
        if (chunkItemMultiplier <= 0) {
            chunkItemMultiplier = 1.0;
        }

        loadEnumSet(ignoredTypes, limits.getStringList("ignored-types"), EntityType.class);
        loadEnumSet(ignoredItems, limits.getStringList("ignored-items"), Material.class);

        ConfigurationSection custom = limits.getConfigurationSection("custom-limits");
        if (custom != null) {
            custom.getKeys(false).forEach(k ->
                    customLimits.put(k.toUpperCase(), custom.getInt(k)));
        }

        ConfigurationSection settings = config.getConfigurationSection("settings");
        if (settings != null) {
            notifications = settings.getString("send-notifications", "all");
            if (!Arrays.asList("all", "staff", "none").contains(notifications.toLowerCase())) {
                notifications = "all";
            }

            notifyThreshold = Math.min(100, Math.max(0, settings.getInt("notify-threshold", 90)));
            thresholdRatio = notifyThreshold / 100.0;
            notifyCooldown = settings.getInt("notify-cooldown", 10);
            notificationRadius = Math.max(0, Math.min(1000, settings.getDouble("notification-radius", 128.0)));
            performanceMonitoring = settings.getBoolean("performance-monitoring", false);
            debugMode = settings.getBoolean("debug-mode", false);

            ConfigurationSection protection = config.getConfigurationSection("protection");
            if (protection != null) {
                protectNamedEntities = protection.getBoolean("protect-named-entities", true);
                protectLeashedEntities = protection.getBoolean("protect-leashed-entities", true);
                protectTamedAnimals = protection.getBoolean("protect-tamed-animals", true);
                protectEquippedEntities = protection.getBoolean("protect-equipped-entities", true);
                protectBossEntities = protection.getBoolean("protect-boss-entities", true);
            }
        } else {
            notifications = "all";
            notifyThreshold = 90;
            thresholdRatio = 0.9;
            notifyCooldown = 10;
            notificationRadius = 128.0;
            performanceMonitoring = false;
            debugMode = false;
        }
    }

    /**
     * 加载语言消息
     */
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
        msgNotificationAll = parseMessage(messages.getOrDefault("notification-all", ""));
        msgNotificationStaff = parseMessage(messages.getOrDefault("notification-staff", ""));
        msgNotificationNone = parseMessage(messages.getOrDefault("notification-none", ""));
        msgUnknownNotificationStatus = parseMessage(messages.getOrDefault("unknown-notification-status", ""));
        msgGroupCleanupReport = parseMessage(messages.getOrDefault("group-cleanup-report", ""));
        msgGroupPreOverload = parseMessage(messages.getOrDefault("group-pre-overload", ""));
        msgPerfPhase1 = parseMessage(messages.getOrDefault("perf-phase1", ""));
        msgPerfPhase2 = parseMessage(messages.getOrDefault("perf-phase2", ""));
        msgPerfTotal = parseMessage(messages.getOrDefault("perf-total", ""));
        msgPerfClassify = parseMessage(messages.getOrDefault("perf-classify", ""));
        msgPerfCleanup = parseMessage(messages.getOrDefault("perf-cleanup", ""));
        msgPerfHeader = parseMessage(messages.getOrDefault("perf-header", ""));
        msgPerfNoData = parseMessage(messages.getOrDefault("perf-no-data", ""));
        msgPerfDisabled = parseMessage(messages.getOrDefault("perf-disabled", ""));
        msgPerfReset = parseMessage(messages.getOrDefault("perf-reset", ""));
        msgProtectedStats = parseMessage(messages.getOrDefault("protected-stats", ""));
        msgProtectedNamed = parseMessage(messages.getOrDefault("protected-named", ""));
        msgProtectedLeashed = parseMessage(messages.getOrDefault("protected-leashed", ""));
        msgProtectedTamed = parseMessage(messages.getOrDefault("protected-tamed", ""));
        msgProtectedTotal = parseMessage(messages.getOrDefault("protected-total", ""));
        msgProtectedEquipped = parseMessage(messages.getOrDefault("protected-equipped", ""));
        msgProtectedBoss = parseMessage(messages.getOrDefault("protected-boss", ""));
    }

    /**
     * 解析消息中的颜色代码
     */
    private String parseMessage(String raw) {
        return raw != null ? ChatColor.translateAlternateColorCodes('&', raw) : "";
    }

    /**
     * 加载枚举集合
     */
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

    /**
     * 设置清理任务
     */
    private void setupCleanupTask() {
        Runnable task = this::processAllChunks;
        if (IS_FOLIA) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, t -> task.run(), checkInterval, checkInterval);
        } else {
            getServer().getScheduler().scheduleSyncRepeatingTask(this, task, checkInterval, checkInterval);
        }
    }

    /**
     * 调度区域任务
     */
    private void scheduleRegionalTask(World world, int chunkX, int chunkZ, Runnable task) {
        if (IS_FOLIA) {
            try {
                if (world == null) {
                    getLogger().warning("Cannot schedule task for null world");
                    return;
                }

                if (Math.abs(chunkX) > 29999984 || Math.abs(chunkZ) > 29999984) {
                    getLogger().warning("Chunk coordinates out of range: " + chunkX + "," + chunkZ);
                    return;
                }

                getServer().getRegionScheduler().run(this, world, chunkX, chunkZ, scheduledTask -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Error in regional task execution", e);
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Failed to schedule regional task for chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
                try {
                    getServer().getGlobalRegionScheduler().run(this, scheduledTask -> {
                        try {
                            task.run();
                        } catch (Exception taskE) {
                            getLogger().log(Level.WARNING, "Error in fallback task execution", taskE);
                        }
                    });
                } catch (Exception fallbackE) {
                    getLogger().log(Level.SEVERE, "Both regional and global scheduling failed", fallbackE);
                }
            }
        } else {
            try {
                task.run();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error executing task in Paper/Spigot", e);
            }
        }
    }

    /**
     * 调度位置任务
     */
    private void scheduleLocationTask(Location location, Runnable task) {
        if (IS_FOLIA) {
            try {
                getServer().getRegionScheduler().execute(this, location, task);
            } catch (Exception e) {
                getLogger().warning("Failed to schedule location task at " + location + ": " + e.getMessage());
                getServer().getGlobalRegionScheduler().run(this, scheduledTask -> task.run());
            }
        } else {
            try {
                task.run();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error executing location task in Paper/Spigot", e);
            }
        }
    }

    /**
     * 处理所有区块
     */
    private void processAllChunks() {
        long totalStart = System.currentTimeMillis();

        try {
            if (IS_FOLIA) {
                processChunksWithFolia(totalStart);
            } else {
                processChunksWithPaper(totalStart);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Critical error in chunk processing", e);
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
        }
    }

    private volatile long lastPhase1Duration = 0;
    private volatile long lastPhase2Duration = 0;

    /**
     * Folia 环境下处理区块
     */
    private void processChunksWithFolia(long totalStart) {
        Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger totalChunks = new AtomicInteger(0);

        Map<World, List<Chunk>> worldChunks = new ConcurrentHashMap<>();

        try {
            synchronized (this) {
                for (World world : getServer().getWorlds()) {
                    if (world == null) {
                        getLogger().warning("Encountered null world during chunk collection");
                        continue;
                    }

                    try {
                        if (!world.getName().isEmpty() && world.getEnvironment() != null) {
                            Chunk[] chunks = world.getLoadedChunks();
                            if (chunks != null && chunks.length > 0) {
                                List<Chunk> validChunks = Arrays.stream(chunks)
                                        .filter(chunk -> chunk != null && chunk.isLoaded())
                                        .limit(5000)
                                        .collect(Collectors.toList());

                                if (!validChunks.isEmpty()) {
                                    worldChunks.put(world, validChunks);
                                    totalChunks.addAndGet(validChunks.size());
                                }
                            }
                        }
                    } catch (Exception e) {
                        getLogger().warning("Failed to get chunks for world " + world.getName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Critical error during world chunk collection", e);
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            return;
        }

        if (totalChunks.get() == 0) {
            debug("No loaded chunks to process");
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            return;
        }

        long phase1Start = System.currentTimeMillis();
        debug("Starting phase 1: processing " + totalChunks.get() + " chunks");

        AtomicInteger completedTasks = new AtomicInteger(0);
        AtomicBoolean phase1Completed = new AtomicBoolean(false);

        worldChunks.forEach((world, chunks) -> {
            if (world == null || chunks == null) return;

            for (Chunk chunk : chunks) {
                if (chunk == null) {
                    int completed = completedTasks.incrementAndGet();
                    checkPhase1Completion(completed, totalChunks.get(), phase1Completed,
                            globalProcessed, totalStart, phase1Start);
                    continue;
                }

                try {
                    scheduleRegionalTaskWithRetry(world, chunk.getX(), chunk.getZ(), () -> {
                        try {
                            if (chunk != null && chunk.isLoaded() && chunk.getWorld() != null) {
                                processSingleChunk(chunk);
                                processedCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            getLogger().log(Level.WARNING, "Error processing chunk: " +
                                    chunk.getX() + "," + chunk.getZ(), e);
                        } finally {
                            int completed = completedTasks.incrementAndGet();
                            checkPhase1Completion(completed, totalChunks.get(), phase1Completed,
                                    globalProcessed, totalStart, phase1Start);
                        }
                    }, 3);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error scheduling chunk task: " +
                            chunk.getX() + "," + chunk.getZ(), e);
                    int completed = completedTasks.incrementAndGet();
                    checkPhase1Completion(completed, totalChunks.get(), phase1Completed,
                            globalProcessed, totalStart, phase1Start);
                }
            }
        });

        long timeoutTicks = Math.max(600L, Math.min(2400L, totalChunks.get() / 3));
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            if (!phase1Completed.get()) {
                getLogger().warning(String.format("Phase 1 timeout after %d ticks: only %d/%d chunks completed",
                        timeoutTicks, completedTasks.get(), totalChunks.get()));

                if (phase1Completed.compareAndSet(false, true)) {
                    long phase1Duration = System.currentTimeMillis() - phase1Start;
                    lastPhase1Duration = phase1Duration;
                    recordPerformance("Phase1-SingleChunks", phase1Duration);

                    if (chunkCheckRadius > 0) {
                        try {
                            processPhase2Folia(globalProcessed, totalStart);
                        } catch (Exception e) {
                            getLogger().log(Level.WARNING, "Error in forced phase 2 start", e);
                            recordPerformance("Total-Cleanup", lastPhase1Duration);
                        }
                    } else {
                        recordPerformance("Total-Cleanup", lastPhase1Duration);
                    }
                }
            }
        }, timeoutTicks);
    }

    /**
     * 带重试的区域任务调度
     */
    private void scheduleRegionalTaskWithRetry(World world, int chunkX, int chunkZ, Runnable task, int maxRetries) {
        scheduleRegionalTaskWithRetry(world, chunkX, chunkZ, task, maxRetries, 0);
    }

    private void scheduleRegionalTaskWithRetry(World world, int chunkX, int chunkZ, Runnable task, int maxRetries, int currentAttempt) {
        if (currentAttempt >= maxRetries) {
            getLogger().warning("Failed to schedule task after " + maxRetries + " attempts for chunk " + chunkX + "," + chunkZ);
            try {
                task.run();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error in fallback task execution", e);
            }
            return;
        }

        try {
            if (IS_FOLIA) {
                getServer().getRegionScheduler().run(this, world, chunkX, chunkZ, scheduledTask -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Error in regional task execution", e);
                    }
                });
            } else {
                task.run();
            }
        } catch (Exception e) {
            getLogger().warning("Scheduling attempt " + (currentAttempt + 1) + " failed for chunk " +
                    chunkX + "," + chunkZ + ": " + e.getMessage());

            if (currentAttempt < maxRetries - 1) {
                long delay = Math.min(20L * (1L << currentAttempt), 100L);

                if (IS_FOLIA) {
                    getServer().getGlobalRegionScheduler().runDelayed(this,
                            retryTask -> scheduleRegionalTaskWithRetry(world, chunkX, chunkZ, task, maxRetries, currentAttempt + 1),
                            delay);
                } else {
                    scheduleRegionalTaskWithRetry(world, chunkX, chunkZ, task, maxRetries, currentAttempt + 1);
                }
            } else {
                try {
                    task.run();
                } catch (Exception fallbackE) {
                    getLogger().log(Level.WARNING, "Final fallback execution failed", fallbackE);
                }
            }
        }
    }

    /**
     * 检查阶段1是否完成
     */
    private void checkPhase1Completion(int completed, int total, AtomicBoolean phase1Completed,
                                       Set<Chunk> globalProcessed, long totalStart, long phase1Start) {
        if (completed >= total && phase1Completed.compareAndSet(false, true)) {
            try {
                long phase1Duration = System.currentTimeMillis() - phase1Start;
                lastPhase1Duration = phase1Duration;
                recordPerformance("Phase1-SingleChunks", phase1Duration);
                debug("Phase 1 completed in " + phase1Duration + "ms");

                if (chunkCheckRadius > 0) {
                    getServer().getGlobalRegionScheduler().runDelayed(this,
                            task -> {
                                try {
                                    processPhase2Folia(globalProcessed, totalStart);
                                } catch (Exception e) {
                                    getLogger().log(Level.WARNING, "Error in delayed phase 2 processing", e);
                                    recordPerformance("Total-Cleanup", lastPhase1Duration);
                                } finally {
                                    globalProcessed.clear();
                                }
                            }, 10L);
                } else {
                    recordPerformance("Total-Cleanup", lastPhase1Duration);
                    globalProcessed.clear();
                    debug("Cleanup completed, no phase 2 needed");
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error in phase 1 completion", e);
                recordPerformance("Total-Cleanup", lastPhase1Duration);
                globalProcessed.clear();
            }
        }
    }

    /**
     * Folia 环境下处理阶段2
     */
    private void processPhase2Folia(Set<Chunk> globalProcessed, long totalStart) {
        long phase2Start = System.currentTimeMillis();
        debug("Starting phase 2: processing player chunk groups");

        List<Player> onlinePlayers = new ArrayList<>(getServer().getOnlinePlayers());

        if (onlinePlayers.isEmpty()) {
            debug("No online players, skipping phase 2");
            lastPhase2Duration = 0L;
            recordPerformance("Phase2-GroupChunks", 0L);
            recordPerformance("Total-Cleanup", lastPhase1Duration);
            return;
        }

        AtomicInteger playersProcessed = new AtomicInteger(0);
        final int totalPlayers = onlinePlayers.size();

        debug("Processing chunk groups for " + totalPlayers + " players");

        for (Player player : onlinePlayers) {
            if (!player.isOnline()) {
                int processed = playersProcessed.incrementAndGet();
                if (processed == totalPlayers) {
                    finishPhase2(phase2Start, totalStart);
                }
                continue;
            }

            try {
                Location playerLoc = player.getLocation();
                if (playerLoc.getWorld() == null) {
                    int processed = playersProcessed.incrementAndGet();
                    if (processed == totalPlayers) {
                        finishPhase2(phase2Start, totalStart);
                    }
                    continue;
                }

                scheduleLocationTask(playerLoc, () -> {
                    try {
                        processPlayerChunkGroup(player, globalProcessed);
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Error processing player chunk group for: " + player.getName(), e);
                    } finally {
                        int processed = playersProcessed.incrementAndGet();
                        if (processed % 10 == 0 || processed == totalPlayers) {
                            debug(String.format("Phase 2 progress: %d/%d players processed", processed, totalPlayers));
                        }

                        if (processed == totalPlayers) {
                            finishPhase2(phase2Start, totalStart);
                        }
                    }
                });
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error scheduling player task for: " + player.getName(), e);
                int processed = playersProcessed.incrementAndGet();
                if (processed == totalPlayers) {
                    finishPhase2(phase2Start, totalStart);
                }
            }
        }

        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            if (playersProcessed.get() < totalPlayers) {
                getLogger().warning(String.format("Phase 2 timeout: only %d/%d players processed",
                        playersProcessed.get(), totalPlayers));
                finishPhase2(phase2Start, totalStart);
            }
        }, 100L);
    }

    /**
     * 完成阶段2
     */
    private void finishPhase2(long phase2Start, long totalStart) {
        long phase2Duration = System.currentTimeMillis() - phase2Start;
        lastPhase2Duration = phase2Duration;
        recordPerformance("Phase2-GroupChunks", phase2Duration);

        long totalCleanupTime = lastPhase1Duration + lastPhase2Duration;
        recordPerformance("Total-Cleanup", totalCleanupTime);

        debug(String.format("Phase 2 completed in %dms, total cleanup time: %dms (Phase1: %dms + Phase2: %dms)",
                phase2Duration, totalCleanupTime, lastPhase1Duration, lastPhase2Duration));
    }

    /**
     * Paper 环境下处理区块
     */
    private void processChunksWithPaper(long totalStart) {
        Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();

        long phase1Start = System.currentTimeMillis();

        List<Chunk> allChunks = new ArrayList<>();
        for (World world : getServer().getWorlds()) {
            Collections.addAll(allChunks, world.getLoadedChunks());
        }

        debug("Processing " + allChunks.size() + " loaded chunks");

        for (Chunk chunk : allChunks) {
            try {
                processSingleChunk(chunk);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error processing chunk: " + chunk, e);
            }
        }

        long phase1End = System.currentTimeMillis();
        recordPerformance("Phase1-SingleChunks", phase1End - phase1Start);

        if (chunkCheckRadius > 0) {
            getServer().getScheduler().runTaskLater(this, () -> {
                try {
                    long phase2Start = System.currentTimeMillis();

                    List<Player> onlinePlayers = new ArrayList<>(getServer().getOnlinePlayers());
                    for (Player player : onlinePlayers) {
                        processPlayerChunkGroup(player, globalProcessed);
                    }

                    long phase2End = System.currentTimeMillis();
                    recordPerformance("Phase2-GroupChunks", phase2End - phase2Start);

                    long totalCleanupTime = (phase1End - phase1Start) + (phase2End - phase2Start);
                    recordPerformance("Total-Cleanup", totalCleanupTime);

                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error in phase 2 processing", e);
                }
            }, 20L);
        } else {
            recordPerformance("Total-Cleanup", phase1End - phase1Start);
        }
    }

    /**
     * 处理玩家周围的区块组
     */
    private void processPlayerChunkGroup(Player player, Set<Chunk> globalProcessed) {
        if (chunkCheckRadius <= 0) return;

        final Player finalPlayer = player;
        if (!finalPlayer.isOnline()) {
            debug("Player " + finalPlayer.getName() + " went offline during processing");
            return;
        }

        final Location loc = finalPlayer.getLocation();
        if (loc == null || loc.getWorld() == null) {
            getLogger().warning("Player " + finalPlayer.getName() + " has null location or world");
            return;
        }

        if (!this.isEnabled()) return;

        getServer().getRegionScheduler().run(this, loc, task -> {
            if (!finalPlayer.isOnline()) {
                debug("Player " + finalPlayer.getName() + " went offline during region scheduling");
                return;
            }

            final int currentRadius = chunkCheckRadius;
            if (currentRadius <= 0) return;

            try {
                Chunk center = loc.getChunk();
                if (center == null || !center.isLoaded()) {
                    getLogger().warning("Cannot get valid chunk for player " + finalPlayer.getName());
                    return;
                }

                World world = loc.getWorld();
                if (world == null) {
                    getLogger().warning("World is null for player " + finalPlayer.getName());
                    return;
                }

                List<Chunk> chunkGroup = new ArrayList<>();
                int skippedChunks = 0;
                int duplicateChunks = 0;
                int invalidChunks = 0;

                int centerX = center.getX();
                int centerZ = center.getZ();

                for (int x = -currentRadius; x <= currentRadius; x++) {
                    for (int z = -currentRadius; z <= currentRadius; z++) {
                        try {
                            int chunkX = centerX + x;
                            int chunkZ = centerZ + z;

                            if (Math.abs(chunkX) > 1875000 || Math.abs(chunkZ) > 1875000) {
                                skippedChunks++;
                                continue;
                            }

                            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                                skippedChunks++;
                                continue;
                            }

                            Chunk chunk = world.getChunkAt(chunkX, chunkZ);

                            if (chunk == null) {
                                skippedChunks++;
                                continue;
                            }

                            if (!chunk.isLoaded()) {
                                skippedChunks++;
                                continue;
                            }

                            if (chunk.getX() != chunkX || chunk.getZ() != chunkZ) {
                                getLogger().warning(String.format("Chunk coordinate mismatch: expected (%d,%d), got (%d,%d)",
                                        chunkX, chunkZ, chunk.getX(), chunk.getZ()));
                                invalidChunks++;
                                continue;
                            }

                            synchronized (globalProcessed) {
                                if (globalProcessed.add(chunk)) {
                                    chunkGroup.add(chunk);
                                } else {
                                    duplicateChunks++;
                                }
                            }
                        } catch (Exception e) {
                            getLogger().warning(String.format("Error processing chunk offset (%d,%d) for player %s: %s",
                                    x, z, finalPlayer.getName(), e.getMessage()));
                            invalidChunks++;
                        }
                    }
                }

                debug(String.format("Player %s chunk group: %d collected, %d skipped, %d duplicates, %d invalid",
                        finalPlayer.getName(), chunkGroup.size(), skippedChunks, duplicateChunks, invalidChunks));

                if (!chunkGroup.isEmpty()) {
                    try {
                        processChunkGroupWithWarning(chunkGroup);
                        debug(String.format("Processed chunk group for player %s with %d chunks",
                                finalPlayer.getName(), chunkGroup.size()));
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Error processing chunk group for player " + finalPlayer.getName(), e);
                    }
                } else {
                    debug("No new chunks to process for player " + finalPlayer.getName());
                }

            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error in region task for player: " +
                        finalPlayer.getName(), e);
            }
        });
    }

    /**
     * 处理区块组并发出警告
     */
    private void processChunkGroupWithWarning(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;

        try {
            long classifyStart = System.currentTimeMillis();

            Map<EntityType, List<Entity>> mobs = new HashMap<>();
            List<Entity> allItems = new ArrayList<>();
            int totalProcessed = 0;
            int failedChunks = 0;

            for (Chunk chunk : chunks) {
                if (!chunk.isLoaded()) {
                    failedChunks++;
                    continue;
                }

                try {
                    Entity[] entities = chunk.getEntities();

                    // 优化：预先过滤并分类
                    for (Entity entity : entities) {
                        if (entity instanceof Player) continue;

                        // 统一的有效性检查
                        boolean isValid = false;
                        if (IS_FOLIA) {
                            try {
                                isValid = entity != null && entity.isValid() && !entity.isDead();
                            } catch (IllegalStateException ex) {
                                continue;
                            }
                        } else {
                            isValid = entity != null && entity.isValid() && !entity.isDead();
                        }

                        if (!isValid) continue;

                        try {
                            // 快速分类
                            if (entity instanceof Item) {
                                Item item = (Item) entity;
                                ItemStack itemStack = item.getItemStack();
                                if (itemStack != null && itemStack.getType() != Material.AIR &&
                                        !ignoredItems.contains(itemStack.getType())) {
                                    allItems.add(entity);
                                }
                            } else if (entity instanceof LivingEntity) {
                                EntityType type = entity.getType();
                                if (!ignoredTypes.contains(type)) {
                                    mobs.computeIfAbsent(type, k -> new ArrayList<>()).add(entity);
                                }
                            }
                            totalProcessed++;
                        } catch (Exception e) {
                            debug("Error classifying entity: " + entity.getType() + " - " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Error processing chunk " + chunk.getX() + "," + chunk.getZ() + ": " + e.getMessage());
                    failedChunks++;
                }
            }

            recordPerformance("Entity-Classification", System.currentTimeMillis() - classifyStart);

            if (failedChunks > 0) {
                debug(String.format("Failed to process %d chunks in group", failedChunks));
            }

            long cleanupStart = System.currentTimeMillis();

            AtomicInteger removedMobs = new AtomicInteger(0);
            mobs.forEach((type, list) -> {
                if (list.isEmpty()) return;

                try {
                    int singleChunkLimit = getLimitFor(type);
                    int groupLimit = (int) Math.ceil(singleChunkLimit * chunkEntityMultiplier);

                    if (list.size() >= groupLimit * thresholdRatio) {
                        sendGroupWarning(chunks, type.name(), list.size(), groupLimit);
                    }

                    int removed = enforceLimit(list, groupLimit);
                    if (removed > 0) {
                        removedMobs.addAndGet(removed);
                        debug(String.format("Removed %d %s entities from chunk group", removed, type.name()));
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error processing mob type: " + type, e);
                }
            });

            int removedItems = 0;
            if (!allItems.isEmpty()) {
                try {
                    int groupItemLimit = (int) Math.ceil(itemLimit * chunkItemMultiplier);

                    if (allItems.size() >= groupItemLimit * thresholdRatio) {
                        sendGroupWarning(chunks, "Items", allItems.size(), groupItemLimit);
                    }

                    removedItems = enforceLimit(allItems, groupItemLimit);
                    if (removedItems > 0) {
                        debug(String.format("Removed %d items from chunk group", removedItems));
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error processing items in chunk group", e);
                }
            }

            recordPerformance("Cleanup-Enforcement", System.currentTimeMillis() - cleanupStart);

            if (removedMobs.get() + removedItems > 0) {
                sendGroupCleanupReportToNearby(chunks, removedMobs.get(), removedItems);
            }

            debug(String.format("Processed chunk group: %d entities, removed %d mobs and %d items",
                    totalProcessed, removedMobs.get(), removedItems));

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error processing chunk group", e);
        }
    }

    /**
     * 发送区块组警告
     */
    private void sendGroupWarning(List<Chunk> chunks, String typeName, int current, int limit) {
        if (chunks.isEmpty()) return;

        Chunk firstChunk = chunks.get(0);
        if (!isChunkValid(firstChunk)) return;

        World world = firstChunk.getWorld();

        int centerChunkX = chunks.stream().mapToInt(Chunk::getX).sum() / chunks.size();
        int centerChunkZ = chunks.stream().mapToInt(Chunk::getZ).sum() / chunks.size();

        String groupKey = typeName + ":group:" + world.getName() + ":" + centerChunkX + ":" + centerChunkZ;

        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(groupKey, 0L) > notifyCooldown * 1000L) {
            double centerX = centerChunkX * 16 + 8;
            double centerZ = centerChunkZ * 16 + 8;
            Location center = new Location(world, centerX, world.getHighestBlockYAt((int)centerX, (int)centerZ), centerZ);

            Map<String, String> params = new HashMap<>();
            params.put("type", typeName);
            params.put("world", world.getName());
            params.put("centerX", String.valueOf(centerChunkX));
            params.put("centerZ", String.valueOf(centerChunkZ));
            params.put("current", String.valueOf(current));
            params.put("max", String.valueOf(limit));

            String message = replacePlaceholders(msgGroupPreOverload, params);

            sendMessageToNearbyPlayers(center, message, 128);

            lastNotifyTimes.put(groupKey, System.currentTimeMillis());

            if (lastNotifyTimes.size() > 1000) {
                lastNotifyTimes.keySet().removeIf(key ->
                        System.currentTimeMillis() - lastNotifyTimes.get(key) > notifyCooldown * 2000L
                );
            }
        }
    }

    /**
     * 发送区块组清理报告给附近玩家
     */
    private void sendGroupCleanupReportToNearby(List<Chunk> chunks, int removedMobs, int removedItems) {
        if (removedMobs + removedItems == 0 || chunks.isEmpty()) return;

        Chunk firstChunk = chunks.get(0);
        World world = firstChunk.getWorld();
        if (world == null) return;

        int centerChunkX = chunks.stream().mapToInt(Chunk::getX).sum() / chunks.size();
        int centerChunkZ = chunks.stream().mapToInt(Chunk::getZ).sum() / chunks.size();

        int minX = chunks.stream().mapToInt(Chunk::getX).min().orElse(0);
        int maxX = chunks.stream().mapToInt(Chunk::getX).max().orElse(0);
        int minZ = chunks.stream().mapToInt(Chunk::getZ).min().orElse(0);
        int maxZ = chunks.stream().mapToInt(Chunk::getZ).max().orElse(0);

        double centerX = centerChunkX * 16 + 8;
        double centerZ = centerChunkZ * 16 + 8;
        Location center = new Location(world, centerX, world.getHighestBlockYAt((int)centerX, (int)centerZ), centerZ);

        Map<String, String> params = new HashMap<>();
        params.put("mobs", String.valueOf(removedMobs));
        params.put("items", String.valueOf(removedItems));
        if (chunks.size() == 1) {
            params.put("x", String.valueOf(centerChunkX));
            params.put("z", String.valueOf(centerChunkZ));
        } else {
            params.put("x", minX + "~" + maxX);
            params.put("z", minZ + "~" + maxZ);
        }
        params.put("world", world.getName());

        String message = replacePlaceholders(msgGroupCleanupReport, params);

        sendMessageToNearbyPlayers(center, message, 128);

        getLogger().info(ChatColor.stripColor(message));
    }

    /**
     * 发送消息给附近玩家
     */
    private void sendMessageToNearbyPlayers(Location center, String message, double defaultRadius) {
        if (notifications.equalsIgnoreCase("none")) return;
        if (center.getWorld() == null) return;

        double radius = notificationRadius > 0 ? notificationRadius : defaultRadius;

        center.getWorld().getPlayers().stream()
                .filter(p -> {
                    Location pLoc = p.getLocation();
                    double dx = pLoc.getX() - center.getX();
                    double dz = pLoc.getZ() - center.getZ();
                    return Math.sqrt(dx * dx + dz * dz) <= radius;
                })
                .filter(p -> !notifications.equalsIgnoreCase("staff") || p.hasPermission("chunklimiter.notify"))
                .forEach(p -> p.sendMessage(message));
    }

    /**
     * 处理单个区块
     */
    private void processSingleChunk(Chunk chunk) {
        try {
            if (!isChunkValid(chunk)) {
                return;
            }

            long classifyStart = System.currentTimeMillis();

            // 优化：预先过滤无效实体
            List<Entity> validEntities = new ArrayList<>();
            for (Entity e : chunk.getEntities()) {
                if (e instanceof Player) continue;

                // 统一的有效性检查
                if (IS_FOLIA) {
                    try {
                        if (e != null && e.isValid() && !e.isDead()) {
                            validEntities.add(e);
                        }
                    } catch (IllegalStateException ex) {
                        // Folia 线程检查失败，跳过
                        continue;
                    }
                } else {
                    if (e != null && e.isValid() && !e.isDead()) {
                        validEntities.add(e);
                    }
                }
            }

            // 对已过滤的实体进行分类
            Map<EntityCategory, List<Entity>> entities = validEntities.stream()
                    .collect(Collectors.groupingBy(this::classifyEntityFast));

            recordPerformance("Entity-Classification", System.currentTimeMillis() - classifyStart);

            long cleanupStart = System.currentTimeMillis();
            int removedMobs = processMobs(entities.getOrDefault(EntityCategory.MOB, Collections.emptyList()));
            int removedItems = processItems(entities.getOrDefault(EntityCategory.ITEM, Collections.emptyList()));
            recordPerformance("Cleanup-Enforcement", System.currentTimeMillis() - cleanupStart);

            sendCleanupReport(chunk, removedMobs, removedItems);
            checkChunkStatus(chunk);

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "处理区块时出错: " + chunk.getX() + "," + chunk.getZ(), e);
        }
    }

    /**
     * 快速分类实体（假设已经过有效性检查）
     */
    private EntityCategory classifyEntityFast(Entity e) {
        try {
            // 最常见的情况优先检查
            if (e instanceof Item) {
                return EntityCategory.ITEM;
            }

            if (!(e instanceof LivingEntity)) {
                return EntityCategory.OTHER;
            }

            return ignoredTypes.contains(e.getType()) ?
                    EntityCategory.OTHER : EntityCategory.MOB;

        } catch (Exception ex) {
            debug("Failed to classify entity: " + ex.getMessage());
            return EntityCategory.OTHER;
        }
    }

    /**
     * 验证区块是否有效
     */
    private boolean isChunkValid(Chunk chunk) {
        if (chunk == null) return false;

        try {
            if (!chunk.isLoaded()) return false;

            World world = chunk.getWorld();
            if (world == null) return false;

            int x = chunk.getX();
            int z = chunk.getZ();
            if (Math.abs(x) > 1875000 || Math.abs(z) > 1875000) {
                return false;
            }

            return true;
        } catch (Exception e) {
            debug("Error validating chunk: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取实体类型的限制
     */
    private int getLimitFor(EntityType type) {
        if (ignoredTypes.contains(type)) {
            return Integer.MAX_VALUE;
        }
        return customLimits.getOrDefault(type.name(), defaultLimit);
    }

    /**
     * 实体分类枚举
     */
    private enum EntityCategory { MOB, ITEM, OTHER }

    /**
     * 对实体进行分类
     */
    private EntityCategory classifyEntity(Entity e) {
        try {
            if (e == null) return EntityCategory.OTHER;

            if (IS_FOLIA) {
                Location loc = e.getLocation();
                if (loc == null || loc.getWorld() == null) {
                    return EntityCategory.OTHER;
                }

                try {
                    if (!e.isValid() || e.isDead()) {
                        return EntityCategory.OTHER;
                    }
                } catch (Exception ex) {
                    return EntityCategory.OTHER;
                }
            }

            if (e instanceof Item) {
                return EntityCategory.ITEM;
            }

            if (e instanceof Player) {
                return EntityCategory.OTHER;
            }

            if (!(e instanceof LivingEntity)) {
                return EntityCategory.OTHER;
            }

            return ignoredTypes.contains(e.getType()) ?
                    EntityCategory.OTHER : EntityCategory.MOB;

        } catch (Exception ex) {
            debug("Failed to classify entity: " + ex.getMessage());
            return EntityCategory.OTHER;
        }
    }

    /**
     * 处理生物实体
     */
    private int processMobs(List<Entity> mobs) {
        int totalRemoved = 0;
        Map<EntityType, List<Entity>> grouped = new HashMap<>();

        for (Entity e : mobs) {
            if (!e.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                e.getPersistentDataContainer().set(SPAWN_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis());
            }
            grouped.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<EntityType, List<Entity>> entry : grouped.entrySet()) {
            int limit = customLimits.getOrDefault(entry.getKey().name(), defaultLimit);
            int removed = enforceLimit(entry.getValue(), limit);
            removalStats.merge(entry.getKey(), (long)removed, Long::sum);
            totalRemoved += removed;
        }
        return totalRemoved;
    }

    /**
     * 处理物品实体
     */
    private int processItems(List<Entity> items) {
        if (items.isEmpty()) return 0;

        List<Entity> validItems = new ArrayList<>();

        for (Entity item : items) {
            try {
                if (!(item instanceof Item)) continue;
                if (!item.isValid() || item.isDead()) continue;

                Item itemEntity = (Item) item;
                ItemStack itemStack = itemEntity.getItemStack();

                if (itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0) {
                    continue;
                }

                if (ignoredItems.contains(itemStack.getType())) {
                    continue;
                }

                if (!item.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                    item.getPersistentDataContainer().set(SPAWN_TIME_KEY,
                            PersistentDataType.LONG, System.currentTimeMillis());
                }

                validItems.add(item);
            } catch (Exception e) {
                debug("Error processing item entity: " + e.getMessage());
            }
        }

        return enforceLimit(validItems, itemLimit);
    }

    /**
     * 执行实体限制
     */
    private int enforceLimit(List<Entity> entities, int limit) {
        if (entities.size() <= limit) return 0;

        try {
            if (entities.isEmpty()) {
                return 0;
            }

            long filterStart = System.currentTimeMillis();

            List<Entity> validEntities = new ArrayList<>(entities.size());
            List<Entity> removableEntities = new ArrayList<>();

            for (Entity entity : entities) {
                if (entity == null) continue;

                try {
                    if (IS_FOLIA) {
                        try {
                            if (!entity.isValid() || entity.isDead()) continue;
                        } catch (IllegalStateException e) {
                            continue;
                        }
                    } else {
                        if (!entity.isValid() || entity.isDead()) continue;
                    }

                    validEntities.add(entity);

                    if (canRemoveEntityCached(entity)) {
                        removableEntities.add(entity);
                    }
                } catch (Exception e) {
                    debug("Error checking entity " +
                            (entity != null ? entity.getType() : "null") + ": " + e.getMessage());
                }
            }

            long filterDuration = System.currentTimeMillis() - filterStart;
            if (filterDuration > 100) {
                debug(String.format("Entity filtering took %dms for %d entities",
                        filterDuration, entities.size()));
            }

            int protectedCount = validEntities.size() - removableEntities.size();
            debug(String.format("Entity analysis: %d total, %d valid, %d removable, %d protected",
                    entities.size(), validEntities.size(), removableEntities.size(), protectedCount));

            if (protectedCount >= limit) {
                debug(String.format("All entities are protected (%d protected, limit %d)",
                        protectedCount, limit));
                return 0;
            }

            int maxRemovable = validEntities.size() - limit;
            int toRemove = Math.min(maxRemovable, removableEntities.size());

            if (toRemove <= 0) {
                return 0;
            }

            long sortStart = System.currentTimeMillis();
            removableEntities.sort((e1, e2) -> {
                try {
                    long time1 = e1.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY,
                            PersistentDataType.LONG, System.currentTimeMillis());
                    long time2 = e2.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY,
                            PersistentDataType.LONG, System.currentTimeMillis());
                    return Long.compare(time1, time2);
                } catch (Exception ex) {
                    return 0;
                }
            });

            long sortDuration = System.currentTimeMillis() - sortStart;
            if (sortDuration > 50) {
                debug(String.format("Entity sorting took %dms for %d entities",
                        sortDuration, removableEntities.size()));
            }

            List<Location> particleLocations = new ArrayList<>(Math.min(toRemove, 20));
            int actualRemoved = 0;
            int failedRemovals = 0;

            long removalStart = System.currentTimeMillis();

            for (int i = 0; i < toRemove; i++) {
                Entity entity = removableEntities.get(i);
                try {
                    if (entity != null && entity.isValid() && !entity.isDead()) {
                        Location loc = entity.getLocation();
                        if (loc != null && loc.getWorld() != null && particleLocations.size() < 20) {
                            particleLocations.add(loc.clone());
                        }

                        entity.remove();
                        actualRemoved++;
                    } else {
                        failedRemovals++;
                    }
                } catch (Exception e) {
                    debug("Failed to remove entity: " +
                            (entity != null ? entity.getType() : "null") + " - " + e.getMessage());
                    failedRemovals++;
                }
            }

            long removalDuration = System.currentTimeMillis() - removalStart;

            debug(String.format("Entity removal completed: %d removed, %d failed, took %dms",
                    actualRemoved, failedRemovals, removalDuration));

            if (!particleLocations.isEmpty()) {
                spawnRemovalParticles(particleLocations);
            }

            return actualRemoved;

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error enforcing entity limit", e);
            return 0;
        }
    }

    /**
     * 生成移除粒子效果
     */
    private void spawnRemovalParticles(List<Location> locations) {
        if (locations.isEmpty()) return;

        try {
            int maxParticles = Math.min(locations.size(), 15);

            for (int i = 0; i < maxParticles; i++) {
                Location loc = locations.get(i);
                if (loc.getWorld() != null) {
                    loc.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, loc, 2, 0.3, 0.3, 0.3, 0.01);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error spawning removal particles: " + e.getMessage());
        }
    }

    /**
     * 检查实体是否可以被移除
     */
    private boolean canRemoveEntity(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return false;
        }

        try {
            if (!isEntityValid(entity)) {
                return false;
            }

            EntityType entityType = entity.getType();

            if (ignoredTypes.contains(entityType)) {
                return false;
            }

            return !isEntityProtected(entity);

        } catch (Exception e) {
            debug("Error checking entity protection: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查实体是否有效
     */
    private boolean isEntityValid(Entity entity) {
        if (entity == null) return false;

        try {
            if (IS_FOLIA) {
                try {
                    return entity.isValid() && !entity.isDead();
                } catch (IllegalStateException e) {
                    return false;
                }
            } else {
                return entity.isValid() && !entity.isDead();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查实体是否受保护
     */
    private boolean isEntityProtected(Entity entity) {
        if (protectNamedEntities && hasCustomName(entity)) {
            return true;
        }

        if (entity instanceof LivingEntity) {
            return isLivingEntityProtected((LivingEntity) entity);
        }

        return false;
    }

    /**
     * 检查实体是否有自定义名称
     */
    private boolean hasCustomName(Entity entity) {
        try {
            String customName = entity.getCustomName();
            return customName != null && !customName.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查生物实体是否受保护
     */
    private boolean isLivingEntityProtected(LivingEntity living) {
        try {
            if (protectLeashedEntities && living.isLeashed()) {
                return true;
            }

            if (protectTamedAnimals && living instanceof Tameable) {
                try {
                    if (((Tameable) living).isTamed()) {
                        return true;
                    }
                } catch (Exception e) {
                    return false;
                }
            }

            if (hasPlayerPassengers(living)) {
                return true;
            }

            if (protectBossEntities && living instanceof Boss) {
                return true;
            }

            if (protectEquippedEntities && hasSpecialEquipment(living)) {
                return true;
            }

            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 检查实体是否有特殊装备
     */
    private boolean hasSpecialEquipment(LivingEntity entity) {
        try {
            if (entity.getEquipment() == null) {
                return false;
            }

            EntityEquipment equipment = entity.getEquipment();

            ItemStack mainHand = equipment.getItemInMainHand();
            ItemStack offHand = equipment.getItemInOffHand();

            if ((mainHand != null && mainHand.getType() != Material.AIR) ||
                    (offHand != null && offHand.getType() != Material.AIR)) {
                return true;
            }

            ItemStack[] armor = equipment.getArmorContents();
            if (armor != null) {
                for (ItemStack piece : armor) {
                    if (piece != null && piece.getType() != Material.AIR) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            debug("Error checking equipment for entity " + entity.getType() + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * 检查实体是否有玩家乘客
     */
    private boolean hasPlayerPassengers(LivingEntity entity) {
        Set<Entity> visited = new HashSet<>();
        return hasPlayerPassengersInternal(entity, 0, 3, visited);
    }

    private boolean hasPlayerPassengersInternal(LivingEntity entity, int currentDepth, int maxDepth, Set<Entity> visited) {
        if (currentDepth >= maxDepth || entity == null || visited.contains(entity)) {
            return false;
        }

        visited.add(entity);

        try {
            List<Entity> passengers = entity.getPassengers();
            if (passengers != null && !passengers.isEmpty()) {
                for (Entity passenger : passengers) {
                    if (passenger instanceof Player) {
                        return true;
                    }

                    if (passenger instanceof LivingEntity && !visited.contains(passenger)) {
                        if (hasPlayerPassengersInternal((LivingEntity) passenger, currentDepth + 1, maxDepth, visited)) {
                            return true;
                        }
                    }
                }
            }

            if (currentDepth < maxDepth - 1) {
                Entity vehicle = entity.getVehicle();
                if (vehicle instanceof LivingEntity && !visited.contains(vehicle)) {
                    return hasPlayerPassengersInternal((LivingEntity) vehicle, currentDepth + 1, maxDepth, visited);
                }
            }

            return false;
        } catch (Exception e) {
            debug("Error checking passengers at depth " + currentDepth + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * 发送清理报告
     */
    private void sendCleanupReport(Chunk chunk, int removedMobs, int removedItems) {
        if (notifications.equalsIgnoreCase("none")) return;
        if (removedMobs + removedItems == 0) return;

        World world = chunk.getWorld();
        ChunkStats stats = collectChunkStats(chunk);
        int currentMobs = stats.mobCounts.values().stream().mapToInt(Integer::intValue).sum();
        int currentItems = stats.itemCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, String> params = new HashMap<>();
        params.put("mobs", String.valueOf(removedMobs));
        params.put("items", String.valueOf(removedItems));
        params.put("x", String.valueOf(chunk.getX()));
        params.put("z", String.valueOf(chunk.getZ()));
        params.put("world", world.getName());
        params.put("current_mobs", String.valueOf(currentMobs));
        params.put("current_items", String.valueOf(currentItems));

        String message = replacePlaceholders(msgCleanupReport, params);
        getLogger().info(ChatColor.stripColor(message));

        Location chunkCenter = new Location(
                world,
                chunk.getX() * 16 + 8,
                world.getHighestBlockYAt(chunk.getX() * 16 + 8, chunk.getZ() * 16 + 8),
                chunk.getZ() * 16 + 8
        );

        sendMessageToNearbyPlayers(chunkCenter, message, 128);
    }

    /**
     * 替换消息中的占位符
     */
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

    /**
     * 收集区块统计信息（带缓存）
     */
    private ChunkStats collectChunkStats(Chunk chunk) {
        String chunkKey = chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();

        // 快速获取当前实体数量
        Entity[] entities = chunk.getEntities();
        int currentCount = entities.length;

        // 检查缓存
        CachedChunkStats cached = chunkStatsCache.get(chunkKey);
        if (cached != null && cached.isValid(currentCount)) {
            debug("Using cached stats for chunk " + chunkKey);
            return cached.stats;
        }

        // 重新计算
        ChunkStats stats = new ChunkStats();

        for (Entity entity : entities) {
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

        // 更新缓存
        chunkStatsCache.put(chunkKey, new CachedChunkStats(stats, currentCount));

        // 限制缓存大小
        if (chunkStatsCache.size() > 100) {
            cleanOldestStatsCache();
        }

        return stats;
    }

    /**
     * 清理最旧的统计缓存
     */
    private void cleanOldestStatsCache() {
        try {
            List<Map.Entry<String, CachedChunkStats>> entries = new ArrayList<>(chunkStatsCache.entrySet());

            // 按时间戳排序
            entries.sort((e1, e2) -> Long.compare(e1.getValue().timestamp, e2.getValue().timestamp));

            // 保留最新的 50 个
            int toRemove = entries.size() - 50;
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                chunkStatsCache.remove(entries.get(i).getKey());
            }

            debug("Cleaned " + toRemove + " oldest stats cache entries");
        } catch (Exception e) {
            debug("Error cleaning stats cache: " + e.getMessage());
            chunkStatsCache.clear();
        }
    }

    /**
     * 区块统计数据类
     */
    private static class ChunkStats {
        final Map<EntityType, Integer> mobCounts = new HashMap<>();
        final Map<Material, Integer> itemCounts = new HashMap<>();
    }

    /**
     * 检查区块状态
     */
    private void checkChunkStatus(Chunk chunk) {
        if (!isChunkValid(chunk)) return;

        ChunkStats stats = collectChunkStats(chunk);

        stats.mobCounts.forEach((type, count) -> {
            int limit = customLimits.getOrDefault(type.name(), defaultLimit);
            if (count >= limit * thresholdRatio) {
                sendTypeWarning(chunk, type.name(), count, limit);
            }
        });

        int totalItems = stats.itemCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalItems >= itemLimit * thresholdRatio) {
            sendTypeWarning(chunk, "Items", totalItems, itemLimit);
        }
    }

    /**
     * 发送类型警告
     */
    private void sendTypeWarning(Chunk chunk, String typeName, int current, int limit) {
        if (!isChunkValid(chunk)) {
            return;
        }

        World world = chunk.getWorld();
        String worldName = world.getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        String chunkKey = typeName + ":" + worldName + ":" + chunkX + ":" + chunkZ;

        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(chunkKey, 0L) > notifyCooldown * 1000L) {
            String message = msgPreOverload
                    .replace("%type%", typeName)
                    .replace("%current%", String.valueOf(current))
                    .replace("%max%", String.valueOf(limit))
                    .replace("%chunkX%", String.valueOf(chunkX))
                    .replace("%chunkZ%", String.valueOf(chunkZ))
                    .replace("%world%", worldName);

            Location chunkCenter = new Location(
                    world,
                    chunkX * 16 + 8,
                    world.getHighestBlockYAt(chunkX * 16 + 8, chunkZ * 16 + 8),
                    chunkZ * 16 + 8
            );

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

    /**
     * 显示性能统计
     */
    private void showPerformanceStats(CommandSender sender) {
        if (performanceStats.isEmpty()) {
            sender.sendMessage(msgPerfNoData);
            return;
        }

        sender.sendMessage(msgPerfHeader);

        Map<String, String> statDisplayNames = new LinkedHashMap<>();
        statDisplayNames.put("Phase1-SingleChunks", msgPerfPhase1);
        statDisplayNames.put("Phase2-GroupChunks", msgPerfPhase2);
        statDisplayNames.put("Total-Cleanup", msgPerfTotal);
        statDisplayNames.put("Entity-Classification", msgPerfClassify);
        statDisplayNames.put("Cleanup-Enforcement", msgPerfCleanup);

        for (Map.Entry<String, String> entry : statDisplayNames.entrySet()) {
            String statKey = entry.getKey();
            String displayName = entry.getValue();

            PerformanceStats stats = performanceStats.get(statKey);

            if (stats != null && stats.getCount() > 0) {
                String message = String.format(
                        "&7%s: &a%dms &7(%s: &a%.1fms &7| %s: &b%d&7)",
                        displayName,
                        stats.getLastValue(),
                        currentLang.equals("zh") ? "平均" : "avg",
                        stats.getAverage(),
                        currentLang.equals("zh") ? "次数" : "count",
                        stats.getCount()
                );
                sender.sendMessage(parseMessage(message));
            }
        }
    }

    /**
     * 缓存条目类
     */
    private static class CacheEntry {
        final boolean canRemove;
        final long timestamp;

        CacheEntry(boolean canRemove) {
            this.canRemove = canRemove;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long maxAge) {
            return System.currentTimeMillis() - timestamp > maxAge;
        }
    }

    private final Map<Entity, CacheEntry> protectionCache = Collections.synchronizedMap(new WeakHashMap<>());
    private static final long CACHE_EXPIRE_TIME = 30000;
    private static final int MAX_PROTECTION_CACHE_SIZE = 500;

    /**
     * 使用缓存检查实体是否可移除（带大小限制）
     */
    private boolean canRemoveEntityCached(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (!isEntityValid(entity)) {
            protectionCache.remove(entity);
            return false;
        }

        CacheEntry entry = protectionCache.get(entity);

        if (entry != null && !entry.isExpired(CACHE_EXPIRE_TIME)) {
            return entry.canRemove;
        }

        try {
            boolean result = canRemoveEntity(entity);

            if (isEntityValid(entity)) {
                // 缓存大小限制
                if (protectionCache.size() >= MAX_PROTECTION_CACHE_SIZE) {
                    cleanOldestCacheEntries();
                }
                protectionCache.put(entity, new CacheEntry(result));
            }

            return result;
        } catch (Exception e) {
            debug("Error in cached entity check: " + e.getMessage());
            return false;
        }
    }

    /**
     * 清理最旧的缓存条目
     */
    private void cleanOldestCacheEntries() {
        try {
            List<Map.Entry<Entity, CacheEntry>> entries = new ArrayList<>(protectionCache.entrySet());

            // 按时间戳排序
            entries.sort((e1, e2) -> Long.compare(e1.getValue().timestamp, e2.getValue().timestamp));

            // 移除最旧的 25%
            int toRemove = Math.max(1, entries.size() / 4);
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                protectionCache.remove(entries.get(i).getKey());
            }

            debug("Cleaned " + toRemove + " oldest cache entries");
        } catch (Exception e) {
            debug("Error cleaning cache entries: " + e.getMessage());
            // 出错时清空缓存
            protectionCache.clear();
        }
    }

    /**
     * 设置维护任务
     */
    private void setupMaintenanceTask() {
        Runnable maintenance = () -> {
            try {
                long maintenanceStart = System.currentTimeMillis();

                if (IS_FOLIA) {
                    performMaintenanceFolia();
                } else {
                    performMaintenance();
                }

                long duration = System.currentTimeMillis() - maintenanceStart;
                if (duration > 200) {
                    getLogger().warning("Maintenance took " + duration + "ms, consider optimization");
                }

            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error during maintenance", e);
            }
        };

        long interval = 2400L;
        if (IS_FOLIA) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, t -> maintenance.run(), interval, interval);
        } else {
            getServer().getScheduler().runTaskTimerAsynchronously(this, maintenance, interval, interval);
        }
    }

    /**
     * Folia 环境下的维护
     */
    private void performMaintenanceFolia() {
        long stepStart = System.currentTimeMillis();

        cleanupNotificationRecords();
        checkMaintenanceTime(stepStart, "notification cleanup");

        stepStart = System.currentTimeMillis();
        cleanupProtectionCacheFolia();
        checkMaintenanceTime(stepStart, "protection cache cleanup");

        stepStart = System.currentTimeMillis();
        cleanupStatistics();
        checkMaintenanceTime(stepStart, "statistics cleanup");
    }

    /**
     * 标准环境下的维护
     */
    private void performMaintenance() {
        long stepStart = System.currentTimeMillis();

        cleanupNotificationRecords();
        checkMaintenanceTime(stepStart, "notification cleanup");

        stepStart = System.currentTimeMillis();
        cleanupProtectionCache();
        checkMaintenanceTime(stepStart, "protection cache cleanup");

        stepStart = System.currentTimeMillis();
        cleanupStatistics();
        checkMaintenanceTime(stepStart, "statistics cleanup");
    }

    /**
     * 检查维护步骤耗时
     */
    private void checkMaintenanceTime(long stepStart, String stepName) {
        long duration = System.currentTimeMillis() - stepStart;
        if (duration > 50) {
            debug(stepName + " took " + duration + "ms");
        }
    }

    /**
     * 清理通知记录
     */
    private void cleanupNotificationRecords() {
        int initialSize = lastNotifyTimes.size();
        if (initialSize <= 50) return;

        long expireTime = System.currentTimeMillis() - (notifyCooldown * 2000L);

        List<String> toRemove = new ArrayList<>();
        int batchSize = Math.min(100, initialSize / 4);

        try {
            Iterator<Map.Entry<String, Long>> iterator = lastNotifyTimes.entrySet().iterator();
            int processed = 0;

            while (iterator.hasNext() && processed < batchSize) {
                Map.Entry<String, Long> entry = iterator.next();
                if (entry.getValue() < expireTime) {
                    toRemove.add(entry.getKey());
                }
                processed++;
            }

            for (String key : toRemove) {
                lastNotifyTimes.remove(key);
            }

            if (!toRemove.isEmpty()) {
                debug("Cleaned " + toRemove.size() + " expired notification records (batch size: " + batchSize + ")");
            }

        } catch (Exception e) {
            getLogger().warning("Error during notification cleanup: " + e.getMessage());
        }
    }

    /**
     * 清理保护缓存
     */
    private void cleanupProtectionCache() {
        if (protectionCache.size() <= 50) return;

        if (IS_FOLIA) {
            cleanupProtectionCacheFolia();
        } else {
            cleanupProtectionCacheStandard();
        }
    }

    /**
     * 标准环境下清理保护缓存
     */
    private void cleanupProtectionCacheStandard() {
        protectionCache.entrySet().removeIf(entry -> {
            Entity entity = entry.getKey();
            CacheEntry cache = entry.getValue();

            return entity == null ||
                    !isEntityValid(entity) ||
                    cache.isExpired(CACHE_EXPIRE_TIME);
        });
    }

    /**
     * Folia 环境下清理保护缓存
     */
    private void cleanupProtectionCacheFolia() {
        try {
            long expireTime = System.currentTimeMillis() - CACHE_EXPIRE_TIME;

            // 只清理过期的缓存条目
            protectionCache.entrySet().removeIf(entry -> {
                CacheEntry cache = entry.getValue();
                return cache != null && cache.timestamp < expireTime;
            });

            // 如果缓存仍然过大，清理最旧的条目
            if (protectionCache.size() > MAX_PROTECTION_CACHE_SIZE) {
                cleanOldestCacheEntries();
            }
        } catch (Exception e) {
            getLogger().warning("Error during Folia cache cleanup: " + e.getMessage());
            protectionCache.clear();
        }
    }

    /**
     * 清理统计数据
     */
    private void cleanupStatistics() {
        if (removalStats.size() > 20) {
            try {
                Map<EntityType, Long> topStats = removalStats.entrySet().parallelStream()
                        .filter(entry -> entry.getValue() > 0)
                        .sorted(Map.Entry.<EntityType, Long>comparingByValue().reversed())
                        .limit(15)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (e1, e2) -> e1,
                                LinkedHashMap::new
                        ));

                removalStats.clear();
                removalStats.putAll(topStats);

            } catch (Exception e) {
                getLogger().warning("Error trimming removal statistics: " + e.getMessage());
                removalStats.clear();
            }
        }

        if (performanceStats.size() > 15) {
            Set<String> coreStats = new HashSet<>();
            coreStats.add("Phase1-SingleChunks");
            coreStats.add("Phase2-GroupChunks");
            coreStats.add("Total-Cleanup");
            coreStats.add("Entity-Classification");
            coreStats.add("Cleanup-Enforcement");

            performanceStats.entrySet().removeIf(entry ->
                    !coreStats.contains(entry.getKey()) && entry.getValue().getCount() < 5
            );
        }
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        String langPrefix = currentLang.equals("zh") ? "&6用法：" : "&6Usage:";
        String[] helpMessages = currentLang.equals("zh") ?
                new String[] {
                        "&a/chunklimiter reload &7- 重载配置",
                        "&a/chunklimiter notify [all|staff|none] &7- 切换通知状态",
                        "&a/chunklimiter stats &7- 查看当前区块统计",
                        "&a/chunklimiter performance &7- 查看性能监控",
                        "&a/chunklimiter performance reset &7- 重置性能监控"
                } :
                new String[] {
                        "&a/chunklimiter reload &7- Reload config",
                        "&a/chunklimiter notify [all|staff|none] &7- Toggle notifications",
                        "&a/chunklimiter stats &7- Show chunk stats",
                        "&a/chunklimiter performance &7- View performance monitoring",
                        "&a/chunklimiter performance reset &7- Reset performance monitoring"
                };

        sender.sendMessage(parseMessage(langPrefix));
        for (String msg : helpMessages) {
            sender.sendMessage(parseMessage(msg));
        }
    }

    /**
     * 保护统计类
     */
    private static class ProtectionStats {
        int namedCount = 0;
        int leashedCount = 0;
        int tamedCount = 0;
        int equippedCount = 0;
        int bossCount = 0;
        int totalProtected = 0;

        void addEntity(Entity entity, boolean protectNamed, boolean protectLeashed,
                       boolean protectTamed, boolean protectEquipped, boolean protectBoss,
                       ChunkEntityLimiter plugin) {
            boolean isProtected = false;

            try {
                if (protectNamed && entity.getCustomName() != null && !entity.getCustomName().trim().isEmpty()) {
                    namedCount++;
                    isProtected = true;
                }

                if (entity instanceof LivingEntity) {
                    LivingEntity living = (LivingEntity) entity;

                    if (protectLeashed && living.isLeashed()) {
                        leashedCount++;
                        isProtected = true;
                    }

                    if (protectTamed && living instanceof Tameable && ((Tameable) living).isTamed()) {
                        tamedCount++;
                        isProtected = true;
                    }

                    if (protectEquipped && plugin.hasSpecialEquipment(living)) {
                        equippedCount++;
                        isProtected = true;
                    }

                    if (protectBoss && living instanceof Boss) {
                        bossCount++;
                        isProtected = true;
                    }
                }

                if (isProtected) {
                    totalProtected++;
                }
            } catch (Exception e) {
                // 忽略统计错误
            }
        }
    }

    /**
     * 显示区块统计
     */
    private void showChunkStats(Player player) {
        Chunk chunk = player.getLocation().getChunk();

        showSingleChunkStats(player, chunk);

        if (chunkCheckRadius > 0) {
            showGroupChunkStats(player);
        }
    }

    /**
     * 显示区块组统计
     */
    private void showGroupChunkStats(Player player) {
        Location loc = player.getLocation();
        Chunk center = loc.getChunk();
        World world = center.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        List<Chunk> chunkGroup = new ArrayList<>();
        for (int x = -chunkCheckRadius; x <= chunkCheckRadius; x++) {
            for (int z = -chunkCheckRadius; z <= chunkCheckRadius; z++) {
                try {
                    Chunk chunk = world.getChunkAt(center.getX() + x, center.getZ() + z);
                    if (chunk.isLoaded()) {
                        chunkGroup.add(chunk);
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to get chunk at " + (center.getX() + x) + "," + (center.getZ() + z));
                }
            }
        }

        Map<EntityType, Integer> totalMobs = new HashMap<>();
        Map<EntityType, Integer> ignoredMobCounts = new HashMap<>();
        int totalItemCount = 0;
        int ignoredItemCount = 0;

        for (Chunk chunk : chunkGroup) {
            try {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Player) continue;

                    try {
                        if (entity instanceof LivingEntity) {
                            EntityType type = entity.getType();
                            totalMobs.merge(type, 1, Integer::sum);
                            if (ignoredTypes.contains(type)) {
                                ignoredMobCounts.merge(type, 1, Integer::sum);
                            }
                            protectionStats.addEntity(entity, protectNamedEntities, protectLeashedEntities,
                                    protectTamedAnimals, protectEquippedEntities,
                                    protectBossEntities, this);
                        } else if (entity instanceof Item) {
                            Item item = (Item) entity;
                            ItemStack itemStack = item.getItemStack();
                            if (itemStack != null) {
                                Material type = itemStack.getType();
                                if (ignoredItems.contains(type)) {
                                    ignoredItemCount++;
                                } else {
                                    totalItemCount++;
                                }
                            }
                        }
                    } catch (Exception e) {
                        debug("Error processing entity in stats: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Error processing chunk in stats: " + chunk.getX() + "," + chunk.getZ());
            }
        }

        String header = currentLang.equals("zh") ?
                String.format("&6==== 区块组合统计 (半径: %d, 共%d个区块) ====", chunkCheckRadius, chunkGroup.size()) :
                String.format("&6==== Group Chunk Stats(Radius: %d, %d chunks) ====", chunkCheckRadius, chunkGroup.size());

        player.sendMessage(parseMessage(header));

        if (!totalMobs.isEmpty()) {
            player.sendMessage(parseMessage(msgMobStats));

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

            ignoredMobCounts.forEach((type, count) -> {
                Map<String, String> params = new HashMap<>();
                params.put("type", type.name() + " (ignored)");
                params.put("count", String.valueOf(count));
                params.put("limit", currentLang.equals("zh") ? "无限制" : "Unlimited");

                player.sendMessage(replacePlaceholders(msgMobStatsLine, params));
            });
        }

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

            if (protectionStats.equippedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.equippedCount));
                player.sendMessage(replacePlaceholders(msgProtectedEquipped, params));
            }

            if (protectionStats.bossCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.bossCount));
                player.sendMessage(replacePlaceholders(msgProtectedBoss, params));
            }

            Map<String, String> params = new HashMap<>();
            params.put("count", String.valueOf(protectionStats.totalProtected));
            player.sendMessage(replacePlaceholders(msgProtectedTotal, params));
        }

        int totalMobCount = totalMobs.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, String> totalParams = new HashMap<>();
        totalParams.put("total_mobs", String.valueOf(totalMobCount));
        totalParams.put("total_items", String.valueOf(totalItemCount + ignoredItemCount));
        player.sendMessage(replacePlaceholders(msgTotalStats, totalParams));
    }

    /**
     * 显示单个区块统计
     */
    private void showSingleChunkStats(Player player, Chunk chunk) {
        World world = chunk.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        Map<EntityType, Long> allMobCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof LivingEntity && !(e instanceof Player))
                .peek(e -> protectionStats.addEntity(e, protectNamedEntities, protectLeashedEntities,
                        protectTamedAnimals, protectEquippedEntities,
                        protectBossEntities, this))
                .collect(Collectors.groupingBy(
                        Entity::getType,
                        Collectors.counting()
                ));

        Map<Material, Long> allItemCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof Item)
                .map(e -> ((Item) e).getItemStack().getType())
                .filter(type -> type != Material.AIR)
                .collect(Collectors.groupingBy(
                        material -> material,
                        Collectors.counting()
                ));

        Map<String, String> baseParams = new HashMap<>();
        baseParams.put("x", String.valueOf(chunk.getX()));
        baseParams.put("z", String.valueOf(chunk.getZ()));
        baseParams.put("world", world.getName());

        player.sendMessage(replacePlaceholders(msgChunkHeader, baseParams));

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

            if (protectionStats.equippedCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.equippedCount));
                player.sendMessage(replacePlaceholders(msgProtectedEquipped, params));
            }

            if (protectionStats.bossCount > 0) {
                Map<String, String> params = new HashMap<>();
                params.put("count", String.valueOf(protectionStats.bossCount));
                player.sendMessage(replacePlaceholders(msgProtectedBoss, params));
            }

            Map<String, String> params = new HashMap<>();
            params.put("count", String.valueOf(protectionStats.totalProtected));
            player.sendMessage(replacePlaceholders(msgProtectedTotal, params));
        }

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

    /**
     * 处理通知命令
     */
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

        String[] options = {"all", "staff", "none"};
        if (args.length == 1) {
            synchronized (this) {
                notifications = options[(Arrays.asList(options).indexOf(notifications) + 1) % options.length];
            }
        } else {
            if (!Arrays.asList(options).contains(args[1].toLowerCase())) {
                player.sendMessage(msgUnknownNotificationStatus);
                return;
            }
            notifications = args[1].toLowerCase();
        }
        player.sendMessage(notifications.equalsIgnoreCase("all") ? msgNotificationAll : notifications.equalsIgnoreCase("staff") ? msgNotificationStaff : msgNotificationNone);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = Arrays.asList("reload", "stats", "notify", "help", "performance");
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "notify":
                    return Arrays.asList("all", "staff", "none");
                case "performance":
                    return Collections.singletonList("reset");
            }
        }

        return Collections.emptyList();
    }

    /**
     * 调试日志输出
     */
    private void debug(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (IS_FOLIA) {
                try {
                    getServer().getGlobalRegionScheduler().cancelTasks(this);
                } catch (Exception e) {
                    getLogger().warning("Error canceling Folia tasks: " + e.getMessage());
                }
            } else {
                getServer().getScheduler().cancelTasks(this);
            }

            synchronized (configLock) {
                performanceStats.clear();
                lastNotifyTimes.clear();
                lastPerformanceValues.clear();
                removalStats.clear();
                customLimits.clear();
                ignoredTypes.clear();
                ignoredItems.clear();
                protectionCache.clear();
                chunkStatsCache.clear(); // 清理统计缓存
            }

            getLogger().info("ChunkLimiter v" + getDescription().getVersion() + " disabled successfully");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error during cleanup", e);
        }
    }
}