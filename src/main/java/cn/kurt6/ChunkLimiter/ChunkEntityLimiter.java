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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.bukkit.Bukkit.*;

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    // 性能监控
    private final Map<String, PerformanceStats> performanceStats = new ConcurrentHashMap<>();
    private boolean performanceMonitoring = false;
    private final Map<String, Long> lastPerformanceValues = new ConcurrentHashMap<>();

    private void recordPerformance(String operation, long duration) {
        if (!performanceMonitoring) return;

        try {
            performanceStats.computeIfAbsent(operation, k -> new PerformanceStats())
                    .addValue(duration);
        } catch (Exception e) {
            getLogger().fine("Error recording performance for " + operation + ": " + e.getMessage());
        }
    }

    private static class PerformanceStats {
        private volatile long totalTime = 0;
        private volatile long count = 0;
        private volatile long lastValue = 0;
        private final Object lock = new Object();
        private volatile long minValue = Long.MAX_VALUE;
        private volatile long maxValue = Long.MIN_VALUE;
        private final int maxSamples = 1000;

        // 使用滑动窗口统计
        private final long[] samples = new long[maxSamples];
        private volatile int currentIndex = 0;
        private volatile boolean isFull = false;

        public void addValue(long value) {
            synchronized (lock) {
                lastValue = value;

                // 更新最值
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);

                // 滑动窗口
                if (isFull) {
                    // 减去要被覆盖的旧值
                    totalTime -= samples[currentIndex];
                } else {
                    count++;
                    if (count >= maxSamples) {
                        isFull = true;
                        count = maxSamples;
                    }
                }

                // 添加新值
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
    private boolean protectEquippedEntities = true;
    private boolean protectBossEntities = true;
    private String msgProtectedEquipped;
    private String msgProtectedBoss;

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
        en.put("protected-equipped", " &7Equipped: &a%count%");
        en.put("protected-boss", " &7Boss: &a%count%");

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
        initLanguages(); // 初始化语言数据
        reloadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        setupCleanupTask();
        setupMaintenanceTask();

        // 启动日志
        getLogger().info("ChunkLimiter v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Language: " + currentLang.toUpperCase());
        getLogger().info("Performance monitoring: " + (performanceMonitoring ? "Enabled" : "Disabled"));
        if (IS_FOLIA) {
            getLogger().info("Running on Folia - using regional scheduling");
        }
    }

    private final Object configLock = new Object();

    private volatile boolean reloadInProgress = false;

    private void reloadConfiguration() {
        if (reloadInProgress) {
            getLogger().warning("Configuration reload already in progress");
            return;
        }

        reloadInProgress = true;
        try {
            synchronized (configLock) {
                // 原子性地重载所有配置
                ConfigurationSection oldConfig = getConfig();
                reloadConfig();

                try {
                    loadSettings();
                    loadMessages();
                    getLogger().info("Configuration reloaded successfully");
                } catch (Exception e) {
                    // 回滚到旧配置
                    getLogger().log(Level.SEVERE, "Failed to reload config, keeping old settings", e);
                }
            }
        } finally {
            reloadInProgress = false;
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
            chunkItemMultiplier = 1.0;
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
            protectEquippedEntities = protection.getBoolean("protect-equipped-entities", true);
            protectBossEntities = protection.getBoolean("protect-boss-entities", true);
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
        msgProtectedEquipped = parseMessage(messages.getOrDefault("protected-equipped", ""));
        msgProtectedBoss = parseMessage(messages.getOrDefault("protected-boss", ""));
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

    private void scheduleRegionalTask(World world, int chunkX, int chunkZ, Runnable task) {
        if (IS_FOLIA) {
            try {
                // 验证参数有效性
                if (world == null) {
                    getLogger().warning("Cannot schedule task for null world");
                    return;
                }

                // 检查区块坐标是否在合理范围内
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
                // 回退到全局调度器
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
            // Paper/Spigot 环境直接同步执行
            try {
                task.run();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error executing task in Paper/Spigot", e);
            }
        }
    }

    private void scheduleLocationTask(Location location, Runnable task) {
        if (IS_FOLIA) {
            try {
                getServer().getRegionScheduler().execute(this, location, task);
            } catch (Exception e) {
                getLogger().warning("Failed to schedule location task at " + location + ": " + e.getMessage());
                // 回退到全局调度器
                getServer().getGlobalRegionScheduler().run(this, scheduledTask -> task.run());
            }
        } else {
            // Paper/Spigot 环境直接同步执行
            try {
                task.run();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error executing location task in Paper/Spigot", e);
            }
        }
    }

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
            // 确保即使出错也记录性能统计
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
        }
    }

    private volatile long lastPhase1Duration = 0;
    private volatile long lastPhase2Duration = 0;

    private void processChunksWithFolia(long totalStart) {
        Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger totalChunks = new AtomicInteger(0);

        // 预先收集所有世界的区块信息
        Map<World, List<Chunk>> worldChunks = new ConcurrentHashMap<>();

        try {
            synchronized (this) {
                for (World world : getWorlds()) {
                    if (world == null) {
                        getLogger().warning("Encountered null world during chunk collection");
                        continue;
                    }

                    try {
                        // 世界状态检查
                        if (!world.getName().isEmpty() && world.getEnvironment() != null) {
                            Chunk[] chunks = world.getLoadedChunks();
                            if (chunks != null && chunks.length > 0) {
                                List<Chunk> validChunks = Arrays.stream(chunks)
                                        .filter(chunk -> chunk != null && chunk.isLoaded())
                                        .limit(5000) // 进一步限制处理数量
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
            getLogger().fine("No loaded chunks to process");
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            return;
        }

        long phase1Start = System.currentTimeMillis();
        getLogger().fine("Starting phase 1: processing " + totalChunks.get() + " chunks");

        AtomicInteger completedTasks = new AtomicInteger(0);
        AtomicBoolean phase1Completed = new AtomicBoolean(false);

        // 第一阶段：并行处理单个区块
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
                            // 双重检查区块状态
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
                    }, 3); // 最多重试3次
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error scheduling chunk task: " +
                            chunk.getX() + "," + chunk.getZ(), e);
                    int completed = completedTasks.incrementAndGet();
                    checkPhase1Completion(completed, totalChunks.get(), phase1Completed,
                            globalProcessed, totalStart, phase1Start);
                }
            }
        });

        // 超时保护机制，动态计算超时时间
        long timeoutTicks = Math.max(600L, Math.min(2400L, totalChunks.get() / 3));
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            if (!phase1Completed.get()) {
                getLogger().warning(String.format("Phase 1 timeout after %d ticks: only %d/%d chunks completed",
                        timeoutTicks, completedTasks.get(), totalChunks.get()));

                if (phase1Completed.compareAndSet(false, true)) {
                    long phase1Duration = System.currentTimeMillis() - phase1Start;
                    lastPhase1Duration = phase1Duration; // 保存阶段1耗时
                    recordPerformance("Phase1-SingleChunks", phase1Duration);

                    if (chunkCheckRadius > 0) {
                        try {
                            processPhase2Folia(globalProcessed, totalStart);
                        } catch (Exception e) {
                            getLogger().log(Level.WARNING, "Error in forced phase 2 start", e);
                            // 只记录已完成的阶段时间
                            recordPerformance("Total-Cleanup", lastPhase1Duration);
                        }
                    } else {
                        recordPerformance("Total-Cleanup", lastPhase1Duration);
                    }
                }
            }
        }, timeoutTicks);
    }

    // 重试调度方法
    private void scheduleRegionalTaskWithRetry(World world, int chunkX, int chunkZ, Runnable task, int maxRetries) {
        scheduleRegionalTaskWithRetry(world, chunkX, chunkZ, task, maxRetries, 0);
    }

    private void scheduleRegionalTaskWithRetry(World world, int chunkX, int chunkZ, Runnable task, int maxRetries, int currentAttempt) {
        if (currentAttempt >= maxRetries) {
            getLogger().warning("Failed to schedule task after " + maxRetries + " attempts for chunk " + chunkX + "," + chunkZ);
            // 最后手段：直接同步执行
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

            // 限制重试次数，避免过度调度
            if (currentAttempt < maxRetries - 1) {
                // 使用指数退避延迟
                long delay = Math.min(20L * (1L << currentAttempt), 100L); // 最大延迟5秒

                if (IS_FOLIA) {
                    getServer().getGlobalRegionScheduler().runDelayed(this,
                            retryTask -> scheduleRegionalTaskWithRetry(world, chunkX, chunkZ, task, maxRetries, currentAttempt + 1),
                            delay);
                } else {
                    // Paper环境直接重试
                    scheduleRegionalTaskWithRetry(world, chunkX, chunkZ, task, maxRetries, currentAttempt + 1);
                }
            } else {
                // 最后一次重试失败，直接执行
                try {
                    task.run();
                } catch (Exception fallbackE) {
                    getLogger().log(Level.WARNING, "Final fallback execution failed", fallbackE);
                }
            }
        }
    }

    private void checkPhase1Completion(int completed, int total, AtomicBoolean phase1Completed,
                                       Set<Chunk> globalProcessed, long totalStart, long phase1Start) {
        // 使用 compareAndSet 避免竞争条件
        if (completed >= total && phase1Completed.compareAndSet(false, true)) {
            try {
                long phase1Duration = System.currentTimeMillis() - phase1Start;
                lastPhase1Duration = phase1Duration; // 保存阶段1耗时
                recordPerformance("Phase1-SingleChunks", phase1Duration);
                getLogger().fine("Phase 1 completed in " + phase1Duration + "ms");

                // 延迟启动第二阶段避免并发问题
                if (chunkCheckRadius > 0) {
                    getServer().getGlobalRegionScheduler().runDelayed(this,
                            task -> {
                                try {
                                    processPhase2Folia(globalProcessed, totalStart);
                                } catch (Exception e) {
                                    getLogger().log(Level.WARNING, "Error in delayed phase 2 processing", e);
                                    // 只记录已完成的阶段时间
                                    recordPerformance("Total-Cleanup", lastPhase1Duration);
                                } finally {
                                    globalProcessed.clear();
                                }
                            }, 10L);
                } else {
                    recordPerformance("Total-Cleanup", lastPhase1Duration);
                    globalProcessed.clear();
                    getLogger().fine("Cleanup completed, no phase 2 needed");
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error in phase 1 completion", e);
                recordPerformance("Total-Cleanup", lastPhase1Duration);
                globalProcessed.clear();
            }
        }
    }

    private void processPhase2Folia(Set<Chunk> globalProcessed, long totalStart) {
        long phase2Start = System.currentTimeMillis();
        getLogger().fine("Starting phase 2: processing player chunk groups");

        // 获取在线玩家列表，避免在调度中重复获取
        List<Player> onlinePlayers = new ArrayList<>(getOnlinePlayers());

        if (onlinePlayers.isEmpty()) {
            getLogger().fine("No online players, skipping phase 2");
            lastPhase2Duration = 0L; // 没有第二阶段
            recordPerformance("Phase2-GroupChunks", 0L);
            // 总耗时只是第一阶段
            recordPerformance("Total-Cleanup", lastPhase1Duration);
            return;
        }

        AtomicInteger playersProcessed = new AtomicInteger(0);
        final int totalPlayers = onlinePlayers.size();

        getLogger().fine("Processing chunk groups for " + totalPlayers + " players");

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
                            getLogger().fine(String.format("Phase 2 progress: %d/%d players processed", processed, totalPlayers));
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

        // 第二阶段超时保护
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            if (playersProcessed.get() < totalPlayers) {
                getLogger().warning(String.format("Phase 2 timeout: only %d/%d players processed",
                        playersProcessed.get(), totalPlayers));
                finishPhase2(phase2Start, totalStart);
            }
        }, 100L); // 5秒超时
    }

    private void finishPhase2(long phase2Start, long totalStart) {
        long phase2Duration = System.currentTimeMillis() - phase2Start;
        lastPhase2Duration = phase2Duration; // 保存阶段2耗时
        recordPerformance("Phase2-GroupChunks", phase2Duration);

        // 计算总耗时：阶段1耗时 + 阶段2耗时（不包含调度等待时间）
        long totalCleanupTime = lastPhase1Duration + lastPhase2Duration;
        recordPerformance("Total-Cleanup", totalCleanupTime);

        getLogger().fine(String.format("Phase 2 completed in %dms, total cleanup time: %dms (Phase1: %dms + Phase2: %dms)",
                phase2Duration, totalCleanupTime, lastPhase1Duration, lastPhase2Duration));
    }

    private void processChunksWithPaper(long totalStart) {
        Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();

        long phase1Start = System.currentTimeMillis();

        // 收集所有已加载的区块
        List<Chunk> allChunks = new ArrayList<>();
        for (World world : getWorlds()) {
            Collections.addAll(allChunks, world.getLoadedChunks());
        }

        getLogger().fine("Processing " + allChunks.size() + " loaded chunks");

        // 第一阶段：处理所有单个区块
        for (Chunk chunk : allChunks) {
            try {
                processSingleChunk(chunk);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error processing chunk: " + chunk, e);
            }
        }

        long phase1End = System.currentTimeMillis();
        recordPerformance("Phase1-SingleChunks", phase1End - phase1Start);

        // 第二阶段：处理玩家周围的组合区块
        if (chunkCheckRadius > 0) {
            getServer().getScheduler().runTaskLater(this, () -> {
                try {
                    long phase2Start = System.currentTimeMillis();

                    List<Player> onlinePlayers = new ArrayList<>(getOnlinePlayers());
                    for (Player player : onlinePlayers) {
                        processPlayerChunkGroup(player, globalProcessed);
                    }

                    long phase2End = System.currentTimeMillis();
                    recordPerformance("Phase2-GroupChunks", phase2End - phase2Start);

                    // 计算总耗时：阶段1耗时 + 阶段2耗时
                    long totalCleanupTime = (phase1End - phase1Start) + (phase2End - phase2Start);
                    recordPerformance("Total-Cleanup", totalCleanupTime);

                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error in phase 2 processing", e);
                }
            }, 20L);
        } else {
            // 如果没有第二阶段，总耗时就是第一阶段耗时
            recordPerformance("Total-Cleanup", phase1End - phase1Start);
        }
    }

    private void processPlayerChunkGroup(Player player, Set<Chunk> globalProcessed) {
        if (chunkCheckRadius <= 0) return;

        // 在调度前捕获玩家状态
        final Player finalPlayer = player;
        if (!finalPlayer.isOnline()) {
            getLogger().fine("Player " + finalPlayer.getName() + " went offline during processing");
            return;
        }

        final Location loc = finalPlayer.getLocation();
        if (loc == null || loc.getWorld() == null) {
            getLogger().warning("Player " + finalPlayer.getName() + " has null location or world");
            return;
        }

        // 获取插件实例
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("ChunkEntityLimiter");
        if (plugin == null || !plugin.isEnabled()) return;

        // 使用区域调度器
        Bukkit.getRegionScheduler().run(plugin, loc, task -> {
            // 再次验证玩家状态
            if (!finalPlayer.isOnline()) {
                getLogger().fine("Player " + finalPlayer.getName() + " went offline during region scheduling");
                return;
            }

            // 使用局部变量保存半径（避免并发修改）
            final int currentRadius = chunkCheckRadius;
            if (currentRadius <= 0) return;

            try {
                Chunk center = loc.getChunk();
                if (center == null || !center.isLoaded()) {
                    getLogger().warning("Cannot get valid chunk for player " + finalPlayer.getName());
                    return;
                }

                World world = loc.getWorld();
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

                            // 边界检查
                            if (Math.abs(chunkX) > 1875000 || Math.abs(chunkZ) > 1875000) {
                                skippedChunks++;
                                continue;
                            }

                            // 使用安全的区块获取方式（不自动生成新区块）
                            Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);

                            // 检查区块是否有效
                            if (chunk == null) {
                                skippedChunks++; // 区块未生成
                                continue;
                            }

                            // 检查区块是否已加载
                            if (!chunk.isLoaded()) {
                                skippedChunks++;
                                continue;
                            }

                            // 验证区块坐标是否正确
                            if (chunk.getX() != chunkX || chunk.getZ() != chunkZ) {
                                getLogger().warning(String.format("Chunk coordinate mismatch: expected (%d,%d), got (%d,%d)",
                                        chunkX, chunkZ, chunk.getX(), chunk.getZ()));
                                invalidChunks++;
                                continue;
                            }

                            // 线程安全的去重处理
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

                getLogger().fine(String.format("Player %s chunk group: %d collected, %d skipped, %d duplicates, %d invalid",
                        finalPlayer.getName(), chunkGroup.size(), skippedChunks, duplicateChunks, invalidChunks));

                if (!chunkGroup.isEmpty()) {
                    try {
                        processChunkGroupWithWarning(chunkGroup);
                        getLogger().fine(String.format("Processed chunk group for player %s with %d chunks",
                                finalPlayer.getName(), chunkGroup.size()));
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Error processing chunk group for player " + finalPlayer.getName(), e);
                    }
                } else {
                    getLogger().fine("No new chunks to process for player " + finalPlayer.getName());
                }

            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error in region task for player: " +
                        finalPlayer.getName(), e);
            }
        });
    }

    private void processChunkGroupWithWarning(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;

        try {
            long classifyStart = System.currentTimeMillis();

            // 预分配容量以提高性能
            Map<EntityType, List<Entity>> mobs = new HashMap<>();
            List<Entity> allItems = new ArrayList<>();
            int totalProcessed = 0;
            int failedChunks = 0;

            // 串行处理确保线程安全
            for (Chunk chunk : chunks) {
                if (!chunk.isLoaded()) {
                    failedChunks++;
                    continue;
                }

                try {
                    Entity[] entities = chunk.getEntities();
                    for (Entity entity : entities) {
                        if (entity instanceof Player) continue;
                        if (!entity.isValid() || entity.isDead()) continue;

                        try {
                            EntityCategory category = classifyEntity(entity);
                            if (category == EntityCategory.MOB) {
                                mobs.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
                            } else if (category == EntityCategory.ITEM) {
                                Item item = (Item) entity;
                                ItemStack itemStack = item.getItemStack();
                                if (itemStack != null && itemStack.getType() != Material.AIR &&
                                        !ignoredItems.contains(itemStack.getType())) {
                                    allItems.add(entity);
                                }
                            }
                            totalProcessed++;
                        } catch (Exception e) {
                            getLogger().fine("Error classifying entity: " + entity.getType() + " - " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Error processing chunk " + chunk.getX() + "," + chunk.getZ() + ": " + e.getMessage());
                    failedChunks++;
                }
            }

            // 记录实体分类性能
            recordPerformance("Entity-Classification", System.currentTimeMillis() - classifyStart);

            if (failedChunks > 0) {
                getLogger().fine(String.format("Failed to process %d chunks in group", failedChunks));
            }

            // 开始清理计时
            long cleanupStart = System.currentTimeMillis();

            // 清理生物
            AtomicInteger removedMobs = new AtomicInteger(0);
            mobs.forEach((type, list) -> {
                if (list.isEmpty()) return;

                try {
                    int singleChunkLimit = getLimitFor(type);
                    int groupLimit = (int) Math.ceil(singleChunkLimit * chunkEntityMultiplier);

                    // 预警检查
                    if (list.size() >= groupLimit * thresholdRatio) {
                        sendGroupWarning(chunks, type.name(), list.size(), groupLimit);
                    }

                    int removed = enforceLimit(list, groupLimit);
                    if (removed > 0) {
                        removedMobs.addAndGet(removed);
                        getLogger().fine(String.format("Removed %d %s entities from chunk group", removed, type.name()));
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error processing mob type: " + type, e);
                }
            });

            // 清理物品
            int removedItems = 0;
            if (!allItems.isEmpty()) {
                try {
                    int groupItemLimit = (int) Math.ceil(itemLimit * chunkItemMultiplier);

                    // 物品预警检查
                    if (allItems.size() >= groupItemLimit * thresholdRatio) {
                        sendGroupWarning(chunks, "Items", allItems.size(), groupItemLimit);
                    }

                    removedItems = enforceLimit(allItems, groupItemLimit);
                    if (removedItems > 0) {
                        getLogger().fine(String.format("Removed %d items from chunk group", removedItems));
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error processing items in chunk group", e);
                }
            }

            // 记录清理执行性能
            recordPerformance("Cleanup-Enforcement", System.currentTimeMillis() - cleanupStart);

            // 发送报告给附近玩家
            if (removedMobs.get() + removedItems > 0) {
                sendGroupCleanupReportToNearby(chunks, removedMobs.get(), removedItems);
            }

            getLogger().fine(String.format("Processed chunk group: %d entities, removed %d mobs and %d items",
                    totalProcessed, removedMobs.get(), removedItems));

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error processing chunk group", e);
        }
    }

    private void sendGroupWarning(List<Chunk> chunks, String typeName, int current, int limit) {
        if (chunks.isEmpty()) return;

        Chunk firstChunk = chunks.get(0);
        if (firstChunk == null || !firstChunk.isLoaded()) return;

        World world = firstChunk.getWorld();
        if (world == null) return;

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
        if (center.getWorld() == null) return;

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
        try {
            // 基础验证 - 最常见的无效情况
            if (e == null) return EntityCategory.OTHER;

            // 在 Folia 中安全检查实体状态
            if (IS_FOLIA) {
                // 检查是否在正确的区域线程中
                Location loc = e.getLocation();
                if (loc == null || loc.getWorld() == null) {
                    return EntityCategory.OTHER;
                }

                // 使用安全的状态检查
                try {
                    if (!e.isValid() || e.isDead()) {
                        return EntityCategory.OTHER;
                    }
                } catch (Exception ex) {
                    return EntityCategory.OTHER;
                }
            }

            // 最常见的情况优先检查
            if (e instanceof Item) {
                return EntityCategory.ITEM;
            }

            if (e instanceof Player) {
                return EntityCategory.OTHER;
            }

            // 检查是否为生物实体
            if (!(e instanceof LivingEntity)) {
                return EntityCategory.OTHER;
            }

            // 检查忽略类型 - 放在最后以提高性能
            return ignoredTypes.contains(e.getType()) ?
                    EntityCategory.OTHER : EntityCategory.MOB;

        } catch (Exception ex) {
            getLogger().fine("Failed to classify entity: " + ex.getMessage());
            return EntityCategory.OTHER;
        }
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
        if (items.isEmpty()) return 0;

        List<Entity> validItems = new ArrayList<>();

        for (Entity item : items) {
            try {
                if (!(item instanceof Item)) continue;
                if (!item.isValid() || item.isDead()) continue;

                Item itemEntity = (Item) item;
                ItemStack itemStack = itemEntity.getItemStack();

                // 检查物品堆叠是否有效
                if (itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0) {
                    continue;
                }

                if (ignoredItems.contains(itemStack.getType())) {
                    continue;
                }

                // 添加时间标记（如果不存在）
                if (!item.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                    item.getPersistentDataContainer().set(SPAWN_TIME_KEY,
                            PersistentDataType.LONG, System.currentTimeMillis());
                }

                validItems.add(item);
            } catch (Exception e) {
                getLogger().fine("Error processing item entity: " + e.getMessage());
            }
        }

        return enforceLimit(validItems, itemLimit);
    }

    private int enforceLimit(List<Entity> entities, int limit) {
        if (entities.size() <= limit) return 0;

        try {
            // 提前检查实体数量，避免不必要的处理
            if (entities.isEmpty()) {
                return 0;
            }
            // 预过滤无效实体，提高后续处理效率
            long filterStart = System.currentTimeMillis();

            List<Entity> validEntities = new ArrayList<>(entities.size());
            List<Entity> removableEntities = new ArrayList<>();

            // 先进行基础有效性检查和保护检查
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

                    // 缓存保护检查结果，避免重复计算
                    if (canRemoveEntityCached(entity)) {
                        removableEntities.add(entity);
                    }
                } catch (Exception e) {
                    getLogger().fine("Error checking entity " +
                            (entity != null ? entity.getType() : "null") + ": " + e.getMessage());
                }
            }

            long filterDuration = System.currentTimeMillis() - filterStart;
            if (filterDuration > 100) {
                getLogger().fine(String.format("Entity filtering took %dms for %d entities",
                        filterDuration, entities.size()));
            }

            int protectedCount = validEntities.size() - removableEntities.size();
            getLogger().fine(String.format("Entity analysis: %d total, %d valid, %d removable, %d protected",
                    entities.size(), validEntities.size(), removableEntities.size(), protectedCount));

            // 如果保护的实体已经达到或超过限制，不需要移除任何实体
            if (protectedCount >= limit) {
                getLogger().fine(String.format("All entities are protected (%d protected, limit %d)",
                        protectedCount, limit));
                return 0;
            }

            // 计算需要移除的数量
            int maxRemovable = validEntities.size() - limit;
            int toRemove = Math.min(maxRemovable, removableEntities.size());

            if (toRemove <= 0) {
                return 0;
            }

            // 按生成时间排序（最老的优先移除）
            long sortStart = System.currentTimeMillis();
            removableEntities.sort((e1, e2) -> {
                try {
                    long time1 = e1.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY,
                            PersistentDataType.LONG, System.currentTimeMillis());
                    long time2 = e2.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY,
                            PersistentDataType.LONG, System.currentTimeMillis());
                    return Long.compare(time1, time2);
                } catch (Exception ex) {
                    // 排序异常时保持原顺序
                    return 0;
                }
            });

            long sortDuration = System.currentTimeMillis() - sortStart;
            if (sortDuration > 50) {
                getLogger().fine(String.format("Entity sorting took %dms for %d entities",
                        sortDuration, removableEntities.size()));
            }

            // 批量移除实体，增加更详细的错误处理
            List<Location> particleLocations = new ArrayList<>(Math.min(toRemove, 20)); // 预分配容量
            int actualRemoved = 0;
            int failedRemovals = 0;

            long removalStart = System.currentTimeMillis();

            for (int i = 0; i < toRemove; i++) {
                Entity entity = removableEntities.get(i);
                try {
                    // 移除前再次检查实体状态
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
                    getLogger().fine("Failed to remove entity: " +
                            (entity != null ? entity.getType() : "null") + " - " + e.getMessage());
                    failedRemovals++;
                }
            }

            long removalDuration = System.currentTimeMillis() - removalStart;

            getLogger().fine(String.format("Entity removal completed: %d removed, %d failed, took %dms",
                    actualRemoved, failedRemovals, removalDuration));

            // 生成粒子效果
            if (!particleLocations.isEmpty()) {
                spawnRemovalParticles(particleLocations);
            }

            return actualRemoved;

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error enforcing entity limit", e);
            return 0;
        }
    }

    private void spawnRemovalParticles(List<Location> locations) {
        if (locations.isEmpty()) return;

        try {
            // 限制粒子效果数量以提高性能
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

    private boolean canRemoveEntity(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return false;
        }

        try {
            // 基础状态检查 - 统一处理
            if (!isEntityValid(entity)) {
                return false;
            }

            EntityType entityType = entity.getType();

            // 检查是否为忽略类型
            if (ignoredTypes.contains(entityType)) {
                return false;
            }

            // 使用策略模式进行保护检查
            return !isEntityProtected(entity);

        } catch (Exception e) {
            getLogger().fine("Error checking entity protection: " + e.getMessage());
            return false; // 发生异常时保护实体
        }
    }

    private boolean isEntityValid(Entity entity) {
        if (entity == null) return false;

        try {
            if (IS_FOLIA) {
                // 在Folia中，只有在正确的区域线程中才能安全访问实体状态
                // 如果不在正确线程中，假设实体有效（保守处理）
                try {
                    return entity.isValid() && !entity.isDead();
                } catch (IllegalStateException e) {
                    // 线程检查失败，返回false表示无法验证
                    return false;
                }
            } else {
                return entity.isValid() && !entity.isDead();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isEntityProtected(Entity entity) {
        // 命名保护
        if (protectNamedEntities && hasCustomName(entity)) {
            return true;
        }

        // 生物特定保护
        if (entity instanceof LivingEntity) {
            return isLivingEntityProtected((LivingEntity) entity);
        }

        return false;
    }

    private boolean hasCustomName(Entity entity) {
        try {
            String customName = entity.getCustomName();
            return customName != null && !customName.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLivingEntityProtected(LivingEntity living) {
        try {
            // 拴绳保护
            if (protectLeashedEntities && living.isLeashed()) {
                return true;
            }

            // 驯服保护
            if (protectTamedAnimals && living instanceof Tameable) {
                try {
                    if (((Tameable) living).isTamed()) {
                        return true;
                    }
                } catch (Exception e) {
                    return false;
                }
            }

            // 乘客保护
            if (hasPlayerPassengers(living, 0, 2)) { // 减少递归深度
                return true;
            }

            // Boss保护
            if (protectBossEntities && living instanceof Boss) {
                return true;
            }

            // 装备保护
            if (protectEquippedEntities && hasSpecialEquipment(living)) {
                return true;
            }

            return false;
        } catch (Exception e) {
            return true; // 检查失败时保护实体
        }
    }

    private boolean hasSpecialEquipment(LivingEntity entity) {
        try {
            // 检查实体是否有装备槽
            if (entity.getEquipment() == null) {
                return false;
            }

            EntityEquipment equipment = entity.getEquipment();

            // 检查手持物品
            ItemStack mainHand = equipment.getItemInMainHand();
            ItemStack offHand = equipment.getItemInOffHand();

            if ((mainHand != null && mainHand.getType() != Material.AIR) ||
                    (offHand != null && offHand.getType() != Material.AIR)) {
                return true;
            }

            // 检查护甲
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
            getLogger().fine("Error checking equipment for entity " + entity.getType() + ": " + e.getMessage());
            return true; // 安全起见，假设有特殊装备
        }
    }

    private boolean hasPlayerPassengers(LivingEntity entity) {
        return hasPlayerPassengers(entity, 0, 3); // 调用新的3参数版本
    }

    private boolean hasPlayerPassengers(LivingEntity entity, int currentDepth, int maxDepth) {
        if (currentDepth >= maxDepth) {
            return false;
        }

        // 添加访问过的实体集合，防止循环引用
        Set<Entity> visited = new HashSet<>();
        return hasPlayerPassengersInternal(entity, currentDepth, maxDepth, visited);
    }

    private boolean hasPlayerPassengersInternal(LivingEntity entity, int currentDepth, int maxDepth, Set<Entity> visited) {
        if (currentDepth >= maxDepth || entity == null || visited.contains(entity)) {
            return false;
        }

        visited.add(entity);

        try {
            // 检查直接乘客
            List<Entity> passengers = entity.getPassengers();
            if (passengers != null && !passengers.isEmpty()) {
                for (Entity passenger : passengers) {
                    if (passenger instanceof Player) {
                        return true;
                    }

                    // 递归检查乘客的乘客
                    if (passenger instanceof LivingEntity && !visited.contains(passenger)) {
                        if (hasPlayerPassengersInternal((LivingEntity) passenger, currentDepth + 1, maxDepth, visited)) {
                            return true;
                        }
                    }
                }
            }

            // 检查载具（限制递归深度）
            if (currentDepth < maxDepth - 1) {
                Entity vehicle = entity.getVehicle();
                if (vehicle instanceof LivingEntity && !visited.contains(vehicle)) {
                    return hasPlayerPassengersInternal((LivingEntity) vehicle, currentDepth + 1, maxDepth, visited);
                }
            }

            return false;
        } catch (Exception e) {
            getLogger().fine("Error checking passengers at depth " + currentDepth + ": " + e.getMessage());
            return true; // 发生异常时保守处理
        }
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

        // 本地化显示名称映射
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

    // 缓存的保护检查方法
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
    private static final long CACHE_EXPIRE_TIME = 30000; // 30秒过期

    private boolean canRemoveEntityCached(Entity entity) {
        if (entity == null) {
            return false;
        }

        // 对于无效实体，不使用缓存
        if (!isEntityValid(entity)) {
            protectionCache.remove(entity); // 清理无效缓存
            return false;
        }

        CacheEntry entry = protectionCache.get(entity);

        if (entry != null && !entry.isExpired(CACHE_EXPIRE_TIME)) {
            return entry.canRemove;
        }

        try {
            boolean result = canRemoveEntity(entity);

            // 只缓存有效实体的结果
            if (isEntityValid(entity)) {
                protectionCache.put(entity, new CacheEntry(result));
            }

            return result;
        } catch (Exception e) {
            getLogger().fine("Error in cached entity check: " + e.getMessage());
            return false; // 安全默认值
        }
    }

    // 定期清理缓存
    private void setupMaintenanceTask() {
        Runnable maintenance = () -> {
            try {
                long maintenanceStart = System.currentTimeMillis();

                // 在Folia中，维护任务应该避免直接访问实体
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

    private void performMaintenanceFolia() {
        // Folia环境下的维护任务，避免访问实体状态
        long stepStart = System.currentTimeMillis();

        // 步骤1：清理通知记录
        cleanupNotificationRecords();
        checkMaintenanceTime(stepStart, "notification cleanup");

        // 步骤2：清理保护缓存（使用Folia安全版本）
        stepStart = System.currentTimeMillis();
        cleanupProtectionCacheFolia();
        checkMaintenanceTime(stepStart, "protection cache cleanup");

        // 步骤3：清理统计数据
        stepStart = System.currentTimeMillis();
        cleanupStatistics();
        checkMaintenanceTime(stepStart, "statistics cleanup");
    }

    private void performMaintenance() {
        // 分步骤进行维护，每步检查时间
        long stepStart = System.currentTimeMillis();

        // 步骤1：清理通知记录
        cleanupNotificationRecords();
        checkMaintenanceTime(stepStart, "notification cleanup");

        // 步骤2：清理保护缓存
        stepStart = System.currentTimeMillis();
        cleanupProtectionCache();
        checkMaintenanceTime(stepStart, "protection cache cleanup");

        // 步骤3：清理统计数据
        stepStart = System.currentTimeMillis();
        cleanupStatistics();
        checkMaintenanceTime(stepStart, "statistics cleanup");
    }

    private void checkMaintenanceTime(long stepStart, String stepName) {
        long duration = System.currentTimeMillis() - stepStart;
        if (duration > 50) {
            getLogger().fine(stepName + " took " + duration + "ms");
        }
    }

    private void cleanupNotificationRecords() {
        int initialSize = lastNotifyTimes.size();
        if (initialSize <= 50) return;

        long expireTime = System.currentTimeMillis() - (notifyCooldown * 2000L);

        // 分批处理，避免长时间阻塞
        List<String> toRemove = new ArrayList<>();
        int batchSize = Math.min(100, initialSize / 4); // 动态批次大小

        try {
            // 使用迭代器安全遍历
            Iterator<Map.Entry<String, Long>> iterator = lastNotifyTimes.entrySet().iterator();
            int processed = 0;

            while (iterator.hasNext() && processed < batchSize) {
                Map.Entry<String, Long> entry = iterator.next();
                if (entry.getValue() < expireTime) {
                    toRemove.add(entry.getKey());
                }
                processed++;
            }

            // 批量删除
            for (String key : toRemove) {
                lastNotifyTimes.remove(key);
            }

            if (!toRemove.isEmpty()) {
                getLogger().fine("Cleaned " + toRemove.size() + " expired notification records (batch size: " + batchSize + ")");
            }

        } catch (Exception e) {
            getLogger().warning("Error during notification cleanup: " + e.getMessage());
        }
    }

    private void cleanupProtectionCache() {
        if (protectionCache.size() <= 50) return;

        if (IS_FOLIA) {
            // Folia环境下使用安全的清理方式
            cleanupProtectionCacheFolia();
        } else {
            // Paper/Spigot环境下的原有逻辑
            cleanupProtectionCacheStandard();
        }
    }

    private void cleanupProtectionCacheStandard() {
        // Paper/Spigot环境下
        protectionCache.entrySet().removeIf(entry -> {
            Entity entity = entry.getKey();
            CacheEntry cache = entry.getValue();

            // 清理死亡实体或过期条目
            return entity == null ||
                    !isEntityValid(entity) ||
                    cache.isExpired(CACHE_EXPIRE_TIME);
        });
    }

    private void cleanupProtectionCacheFolia() {
        // Folia
        // 基于时间的清理策略
        long expireTime = System.currentTimeMillis() - CACHE_EXPIRE_TIME;

        try {
            // 只清理过期的缓存条目，不检查实体状态
            protectionCache.entrySet().removeIf(entry -> {
                CacheEntry cache = entry.getValue();
                return cache != null && cache.isExpired(CACHE_EXPIRE_TIME);
            });

            // 如果缓存仍然过大，清理最老的条目
            if (protectionCache.size() > 200) {
                // 转换为列表并按时间排序
                List<Map.Entry<Entity, CacheEntry>> entries = new ArrayList<>(protectionCache.entrySet());
                entries.sort((e1, e2) -> Long.compare(e1.getValue().timestamp, e2.getValue().timestamp));

                // 保留最新的100个条目
                int toRemove = entries.size() - 100;
                for (int i = 0; i < toRemove; i++) {
                    protectionCache.remove(entries.get(i).getKey());
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error during Folia cache cleanup: " + e.getMessage());
            // 发生错误时清空缓存
            protectionCache.clear();
        }
    }

    private void cleanupStatistics() {
        // 清理移除统计
        if (removalStats.size() > 20) {
            try {
                Map<EntityType, Long> topStats = removalStats.entrySet().parallelStream()
                        .filter(entry -> entry.getValue() > 0)
                        .sorted(Map.Entry.<EntityType, Long>comparingByValue().reversed())
                        .limit(15) // 只保留前15个
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

        // 清理性能统计
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
                // 忽略统计错误，不影响主流程
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

        // 统计所有实体（包括被忽略的）
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
                            // 保护统计调用
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
                        getLogger().fine("Error processing entity in stats: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Error processing chunk in stats: " + chunk.getX() + "," + chunk.getZ());
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
                .peek(e -> protectionStats.addEntity(e, protectNamedEntities, protectLeashedEntities,
                        protectTamedAnimals, protectEquippedEntities,
                        protectBossEntities, this))
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

    @Override
    public void onDisable() {
        // 资源清理
        try {
            // 取消所有计划任务
            if (IS_FOLIA) {
                try {
                    getServer().getGlobalRegionScheduler().cancelTasks(this);
                } catch (Exception e) {
                    getLogger().warning("Error canceling Folia tasks: " + e.getMessage());
                }
            } else {
                getServer().getScheduler().cancelTasks(this);
            }

            // 清理缓存
            synchronized (configLock) {
                performanceStats.clear();
                lastNotifyTimes.clear();
                lastPerformanceValues.clear();
                removalStats.clear();
                customLimits.clear();
                ignoredTypes.clear();
                ignoredItems.clear();
                protectionCache.clear();
            }

            getLogger().info("ChunkLimiter v" + getDescription().getVersion() + " disabled successfully");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error during cleanup", e);
        }
    }
}