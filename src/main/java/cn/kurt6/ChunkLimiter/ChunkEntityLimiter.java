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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    private final Map<String, PerformanceStats> performanceStats = new ConcurrentHashMap<>();
    private boolean performanceMonitoring = false;
    private final Map<String, CachedChunkStats> chunkStatsCache = new ConcurrentHashMap<>();

    private enum NotifyScope { NONE, OP, ALL }

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
            return System.currentTimeMillis() - timestamp < 5000 && currentCount == entityCount;
        }
    }

    private static class EntityTimeWrapper implements Comparable<EntityTimeWrapper> {
        final Entity entity;
        final long spawnTime;
        final boolean isProtected;
        final int weight;

        EntityTimeWrapper(Entity entity, NamespacedKey key, boolean isProtected, int weight) {
            this.entity = entity;
            this.spawnTime = entity.getPersistentDataContainer()
                    .getOrDefault(key, PersistentDataType.LONG, System.currentTimeMillis());
            this.isProtected = isProtected;
            this.weight = weight;
        }

        @Override
        public int compareTo(EntityTimeWrapper other) {
            if (this.isProtected != other.isProtected) {
                return this.isProtected ? 1 : -1;
            }
            return Long.compare(this.spawnTime, other.spawnTime);
        }
    }

    private void recordPerformance(String operation, long duration) {
        if (!performanceMonitoring) return;
        try {
            performanceStats.computeIfAbsent(operation, k -> new PerformanceStats()).addValue(duration);
        } catch (Exception ignored) {}
    }

    private void debug(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private static class PerformanceStats {
        private volatile long totalTime = 0;
        private volatile long count = 0;
        private volatile long lastValue = 0;
        private final Object lock = new Object();
        private final int maxSamples = 1000;
        private final long[] samples = new long[maxSamples];
        private volatile int currentIndex = 0;
        private volatile boolean isFull = false;

        public void addValue(long value) {
            synchronized (lock) {
                lastValue = value;
                if (isFull) totalTime -= samples[currentIndex];
                else {
                    count++;
                    if (count >= maxSamples) { isFull = true; count = maxSamples; }
                }
                samples[currentIndex] = value;
                totalTime += value;
                currentIndex = (currentIndex + 1) % maxSamples;
            }
        }

        public double getAverage() {
            synchronized (lock) { return count > 0 ? (double) totalTime / count : 0; }
        }
    }

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

    private String msgReloadSuccess, msgNoPermission, msgPlayerOnly, msgChunkHeader;
    private String msgMobStats, msgMobStatsLine, msgItemStatsLine, msgTotalStats, msgItemStats;
    private String msgCleanupReport, msgPreOverload;
    private String msgScopeSet, msgNotifyStatus;
    private String msgPerfPhase1, msgPerfPhase2, msgPerfTotal, msgPerfClassify, msgPerfCleanup;
    private String msgPerfHeader, msgPerfNoData, msgPerfDisabled, msgPerfReset;
    private boolean protectNamedEntities = true;
    private boolean protectLeashedEntities = true;
    private boolean protectTamedAnimals = true;
    private String msgProtectedStats, msgProtectedNamed, msgProtectedLeashed, msgProtectedTamed, msgProtectedTotal;
    private boolean protectEquippedEntities = true;
    private boolean protectBossEntities = true;
    private String msgProtectedEquipped, msgProtectedBoss, msgGroupCleanupReport, msgGroupPreOverload;

    private volatile NotifyScope cleanupReportScope = NotifyScope.ALL;
    private volatile NotifyScope overloadWarningScope = NotifyScope.OP;
    private volatile boolean opGlobalCleanupReport = false;
    private volatile boolean opGlobalOverloadWarning = false;
    private volatile boolean consoleCleanupReport = true;

    private volatile boolean cleanProtectedIfOverLimit = false;
    private volatile boolean countItemStackAmount = false;
    private volatile boolean cleanAllLoadedChunks = true;

    private int notifyThreshold;
    private double thresholdRatio;
    private int notifyCooldown;
    private final Map<String, Long> lastNotifyTimes = new ConcurrentHashMap<>();
    private final NamespacedKey SPAWN_TIME_KEY = new NamespacedKey(this, "spawnTime");

    private final Map<Integer, CacheEntry> protectionCache = new ConcurrentHashMap<>();
    private final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(\\w+)%");
    private final Map<EntityType, Long> removalStats = new ConcurrentHashMap<>();

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

    @Override
    public void onEnable() {
        int pluginId = 24723;
        Metrics metrics = new Metrics(this, pluginId);

        saveDefaultConfig();
        initLanguages();
        reloadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        setupCleanupTask();
        setupMaintenanceTask();

        getLogger().info("ChunkLimiter v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Env: " + (IS_FOLIA ? "Folia" : "Bukkit/Paper") + " | Debug: " + debugMode);
    }

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
        en.put("cleanup-report", "&6[Cleanup] Cleaned %mobs% mobs & %items% items in %world% (%x%,%z%)\n  &cCurrent: Mobs %current_mobs% | Items %current_items%");
        en.put("scope-set", "&aNotification scope for %type% set to &e%scope%");
        en.put("notify-status", "&6Status: &7Reports: &e%report% &7| Warnings: &e%warning%");
        en.put("total-stats", "&6Total: &c%total_mobs% mobs &6| &b%total_items% items");
        en.put("group-cleanup-report", "&6[Batch Cleanup] Cleaned %mobs% mobs & %items% items near %world% (X:%x%, Z:%z%)");
        en.put("group-pre-overload", "&cWarning! %type% near %world% (%centerX%, %centerZ%) limit: %current%/%max%");
        en.put("perf-phase1", "Phase1-Single");
        en.put("perf-phase2", "Phase2-Group");
        en.put("perf-total", "Total-Time");
        en.put("perf-classify", "Classify");
        en.put("perf-cleanup", "Cleanup");
        en.put("perf-header", "&6=== Performance Stats ===");
        en.put("perf-no-data", "&cNo data");
        en.put("perf-disabled", "&cPerformance monitoring disabled");
        en.put("perf-reset", "&aStats reset");
        en.put("protected-stats", "&6[Protected Entities]");
        en.put("protected-named", " &7Named: &a%count%");
        en.put("protected-leashed", " &7Leashed: &a%count%");
        en.put("protected-tamed", " &7Tamed: &a%count%");
        en.put("protected-total", " &7Total Protected: &a%count%");
        en.put("protected-equipped", " &7Equipped: &a%count%");
        en.put("protected-boss", " &7Boss: &a%count%");

        Map<String, String> zh = new HashMap<>();
        zh.put("reload-success", "&a配置已重载！");
        zh.put("no-permission", "&c无权执行");
        zh.put("player-only", "&c仅限游戏内使用");
        zh.put("chunk-info-header", "&6==== 区块实体统计 &7(世界: %world%) (%x%, %z%) &6====");
        zh.put("mob-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        zh.put("pre-overload", "&c警告！区块 %world% (%chunkX%, %chunkZ%) %type% 即将超限：%current%/%max%");
        zh.put("mob-stats", "&6[生物统计]");
        zh.put("item-stats", "&6[物品统计]");
        zh.put("item-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        zh.put("cleanup-report", "&6[清理报告] 在 %world% (%x%,%z%) 清理: 生物%mobs% / 物品%items%\n  &c剩余: 生物 %current_mobs% | 物品 %current_items%");
        zh.put("scope-set", "&a已将 %type% 的通知范围设置为 &e%scope%");
        zh.put("notify-status", "&6当前状态: &7清理报告: &e%report% &7| 超限警告: &e%warning%");
        zh.put("total-stats", "&6总计: &c%total_mobs% 生物 &6| &b%total_items% 物品");
        zh.put("group-cleanup-report", "&6[区域清理] 在 %world% (X:%x%, Z:%z%) 清理: 生物%mobs% / 物品%items%");
        zh.put("group-pre-overload", "&c警告！区域 %world% (中心: %centerX%, %centerZ%) %type% 接近上限：%current%/%max%");
        zh.put("perf-phase1", "阶段1-单区块");
        zh.put("perf-phase2", "阶段2-区块组");
        zh.put("perf-total", "总耗时");
        zh.put("perf-classify", "分类耗时");
        zh.put("perf-cleanup", "清理耗时");
        zh.put("perf-header", "&6=== 性能统计 ===");
        zh.put("perf-no-data", "&c暂无数据");
        zh.put("perf-disabled", "&c监控未启用");
        zh.put("perf-reset", "&a统计已重置");
        zh.put("protected-stats", "&6[受保护实体]");
        zh.put("protected-named", " &7命名: &a%count%");
        zh.put("protected-leashed", " &7拴绳: &a%count%");
        zh.put("protected-tamed", " &7驯服: &a%count%");
        zh.put("protected-total", " &7总保护: &a%count%");
        zh.put("protected-equipped", " &7装备: &a%count%");
        zh.put("protected-boss", " &7Boss: &a%count%");

        LANGUAGES.put("en", en);
        LANGUAGES.put("zh", zh);
    }

    private final Object configLock = new Object();
    private volatile boolean reloadInProgress = false;

    private void reloadConfiguration() {
        if (reloadInProgress) return;
        reloadInProgress = true;
        try {
            synchronized (configLock) {
                reloadConfig();
                loadSettings();
                loadMessages();
            }
            getLogger().info("Configuration reloaded");
        } catch (Exception e) {
            getLogger().severe("Reload failed: " + e.getMessage());
        } finally {
            reloadInProgress = false;
        }
    }

    private void loadSettings() {
        ConfigurationSection config = getConfig();
        currentLang = config.getString("settings.language", "en").toLowerCase();
        if (!LANGUAGES.containsKey(currentLang)) currentLang = "en";

        ConfigurationSection limits = config.getConfigurationSection("entity-limits");
        defaultLimit = limits.getInt("default-limit", 100);
        itemLimit = limits.getInt("item-limit", 300);
        checkInterval = limits.getInt("check-interval-ticks", 600);
        chunkCheckRadius = Math.max(0, limits.getInt("chunk-check-radius", 0));
        chunkEntityMultiplier = Math.max(0.1, limits.getDouble("chunk_entity_multiplier", 1.0));
        chunkItemMultiplier = Math.max(0.1, limits.getDouble("chunk_item_multiplier", 1.0));
        countItemStackAmount = limits.getBoolean("count-item-stack-amount", false);

        loadEnumSet(ignoredTypes, limits.getStringList("ignored-types"), EntityType.class);
        loadEnumSet(ignoredItems, limits.getStringList("ignored-items"), Material.class);

        ConfigurationSection custom = limits.getConfigurationSection("custom-limits");
        customLimits.clear();
        if (custom != null) {
            custom.getKeys(false).forEach(k -> customLimits.put(k.toUpperCase(), custom.getInt(k)));
        }

        ConfigurationSection settings = config.getConfigurationSection("settings");

        try {
            cleanupReportScope = NotifyScope.valueOf(settings.getString("cleanup-report-scope", "ALL").toUpperCase());
        } catch (IllegalArgumentException e) {
            cleanupReportScope = NotifyScope.ALL;
            getLogger().warning("Invalid cleanup-report-scope in config, defaulting to ALL");
        }

        try {
            overloadWarningScope = NotifyScope.valueOf(settings.getString("overload-warning-scope", "OP").toUpperCase());
        } catch (IllegalArgumentException e) {
            overloadWarningScope = NotifyScope.OP;
            getLogger().warning("Invalid overload-warning-scope in config, defaulting to OP");
        }

        opGlobalCleanupReport = settings.getBoolean("op-global-cleanup-report", false);
        opGlobalOverloadWarning = settings.getBoolean("op-global-overload-warning", false);
        consoleCleanupReport = settings.getBoolean("console-cleanup-report", true);
        cleanProtectedIfOverLimit = settings.getBoolean("clean-protected-if-over-limit", false);
        cleanAllLoadedChunks = settings.getBoolean("clean-all-loaded-chunks", true);

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
    }

    private void loadMessages() {
        Map<String, String> messages = LANGUAGES.get(currentLang);
        msgReloadSuccess = parseMessage(messages.get("reload-success"));
        msgNoPermission = parseMessage(messages.get("no-permission"));
        msgPlayerOnly = parseMessage(messages.get("player-only"));
        msgChunkHeader = parseMessage(messages.get("chunk-info-header"));
        msgMobStats = parseMessage(messages.get("mob-stats"));
        msgMobStatsLine = parseMessage(messages.get("mob-stats-line"));
        msgItemStatsLine = parseMessage(messages.get("item-stats-line"));
        msgTotalStats = parseMessage(messages.get("total-stats"));
        msgItemStats = parseMessage(messages.get("item-stats"));
        msgCleanupReport = parseMessage(messages.get("cleanup-report"));
        msgPreOverload = parseMessage(messages.get("pre-overload"));
        msgScopeSet = parseMessage(messages.get("scope-set"));
        msgNotifyStatus = parseMessage(messages.get("notify-status"));
        msgGroupCleanupReport = parseMessage(messages.get("group-cleanup-report"));
        msgGroupPreOverload = parseMessage(messages.get("group-pre-overload"));
        msgPerfPhase1 = parseMessage(messages.get("perf-phase1"));
        msgPerfPhase2 = parseMessage(messages.get("perf-phase2"));
        msgPerfTotal = parseMessage(messages.get("perf-total"));
        msgPerfClassify = parseMessage(messages.get("perf-classify"));
        msgPerfCleanup = parseMessage(messages.get("perf-cleanup"));
        msgPerfHeader = parseMessage(messages.get("perf-header"));
        msgPerfNoData = parseMessage(messages.get("perf-no-data"));
        msgPerfDisabled = parseMessage(messages.get("perf-disabled"));
        msgPerfReset = parseMessage(messages.get("perf-reset"));
        msgProtectedStats = parseMessage(messages.get("protected-stats"));
        msgProtectedNamed = parseMessage(messages.get("protected-named"));
        msgProtectedLeashed = parseMessage(messages.get("protected-leashed"));
        msgProtectedTamed = parseMessage(messages.get("protected-tamed"));
        msgProtectedTotal = parseMessage(messages.get("protected-total"));
        msgProtectedEquipped = parseMessage(messages.get("protected-equipped"));
        msgProtectedBoss = parseMessage(messages.get("protected-boss"));
    }

    private String parseMessage(String raw) {
        return raw != null ? ChatColor.translateAlternateColorCodes('&', raw) : "";
    }

    private <T extends Enum<T>> void loadEnumSet(Set<T> set, List<String> values, Class<T> enumClass) {
        set.clear();
        values.forEach(str -> {
            try { set.add(Enum.valueOf(enumClass, str.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
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
                if (world == null) return;
                getServer().getRegionScheduler().run(this, world, chunkX, chunkZ, s -> task.run());
            } catch (Exception ignored) {}
        } else {
            task.run();
        }
    }

    private void processAllChunks() {
        long totalStart = System.currentTimeMillis();
        try {
            if (IS_FOLIA) processChunksWithFolia(totalStart);
            else processChunksWithPaper(totalStart);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error in cleanup", e);
        }
    }

    private volatile long lastPhase1Duration = 0;
    private volatile long lastPhase2Duration = 0;

    private void processChunksWithFolia(long totalStart) {
        if (!cleanAllLoadedChunks) {
            lastPhase1Duration = 0;
            recordPerformance("Phase1-SingleChunks", 0);

            if (chunkCheckRadius > 0) {
                processPhase2Folia(new HashSet<>(), totalStart);
            } else {
                recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            }
            return;
        }

        Set<Chunk> globalProcessed = ConcurrentHashMap.newKeySet();
        AtomicInteger totalChunks = new AtomicInteger(0);

        Map<World, List<Chunk>> worldChunks = new HashMap<>();
        for (World world : getServer().getWorlds()) {
            Chunk[] chunks = world.getLoadedChunks();
            if (chunks.length > 0) {
                List<Chunk> chunkList = new ArrayList<>(Math.min(chunks.length, 5000));
                for(Chunk c : chunks) {
                    if(c != null && c.isLoaded()) chunkList.add(c);
                    if(chunkList.size() >= 5000) break;
                }
                worldChunks.put(world, chunkList);
                totalChunks.addAndGet(chunkList.size());
            }
        }

        if (totalChunks.get() == 0) {
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            return;
        }

        long phase1Start = System.currentTimeMillis();
        AtomicInteger completedTasks = new AtomicInteger(0);
        AtomicBoolean phase1Completed = new AtomicBoolean(false);

        worldChunks.forEach((world, chunks) -> {
            for (Chunk chunk : chunks) {
                scheduleRegionalTask(world, chunk.getX(), chunk.getZ(), () -> {
                    try {
                        if (chunk.isLoaded()) processSingleChunk(chunk);
                    } finally {
                        int completed = completedTasks.incrementAndGet();
                        if (completed >= totalChunks.get() && phase1Completed.compareAndSet(false, true)) {
                            finishPhase1Folia(phase1Start, globalProcessed, totalStart);
                        }
                    }
                });
            }
        });

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            if (phase1Completed.compareAndSet(false, true)) {
                finishPhase1Folia(phase1Start, globalProcessed, totalStart);
            }
        }, 1200L);
    }

    private void finishPhase1Folia(long phase1Start, Set<Chunk> globalProcessed, long totalStart) {
        long duration = System.currentTimeMillis() - phase1Start;
        lastPhase1Duration = duration;
        recordPerformance("Phase1-SingleChunks", duration);

        if (chunkCheckRadius > 0) {
            processPhase2Folia(globalProcessed, totalStart);
        } else {
            recordPerformance("Total-Cleanup", duration);
        }
    }

    private void processChunksWithPaper(long totalStart) {
        long phase1Start = System.currentTimeMillis();
        Set<Chunk> globalProcessed = (chunkCheckRadius > 0) ? new HashSet<>() : null;

        if (cleanAllLoadedChunks) {
            for (World world : getServer().getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    if (chunk != null && chunk.isLoaded()) {
                        processSingleChunk(chunk);
                    }
                }
            }
        }

        long phase1End = System.currentTimeMillis();
        lastPhase1Duration = phase1End - phase1Start;
        recordPerformance("Phase1-SingleChunks", lastPhase1Duration);

        if (chunkCheckRadius > 0) {
            long phase2Start = System.currentTimeMillis();
            for (Player player : getServer().getOnlinePlayers()) {
                processPlayerChunkGroup(player, globalProcessed);
            }
            long phase2End = System.currentTimeMillis();
            lastPhase2Duration = phase2End - phase2Start;
            recordPerformance("Phase2-GroupChunks", lastPhase2Duration);
            recordPerformance("Total-Cleanup", (phase1End - phase1Start) + lastPhase2Duration);
        } else {
            recordPerformance("Total-Cleanup", lastPhase1Duration);
        }
    }

    private void processPhase2Folia(Set<Chunk> globalProcessed, long totalStart) {
        long phase2Start = System.currentTimeMillis();
        List<Player> players = new ArrayList<>(getServer().getOnlinePlayers());

        if (players.isEmpty()) {
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
            return;
        }

        AtomicInteger processed = new AtomicInteger(0);
        int total = players.size();

        for (Player p : players) {
            Location loc = p.getLocation();
            if (IS_FOLIA) {
                getServer().getRegionScheduler().execute(this, loc, () -> {
                    try { processPlayerChunkGroup(p, globalProcessed); }
                    finally { checkPhase2Finish(processed, total, phase2Start, totalStart); }
                });
            } else {
                processPlayerChunkGroup(p, globalProcessed);
                checkPhase2Finish(processed, total, phase2Start, totalStart);
            }
        }
    }

    private void checkPhase2Finish(AtomicInteger processed, int total, long phase2Start, long totalStart) {
        if (processed.incrementAndGet() >= total) {
            long p2Duration = System.currentTimeMillis() - phase2Start;
            recordPerformance("Phase2-GroupChunks", p2Duration);
            recordPerformance("Total-Cleanup", System.currentTimeMillis() - totalStart);
        }
    }

    private void processSingleChunk(Chunk chunk) {
        if (!isChunkValid(chunk)) return;

        long classifyStart = System.currentTimeMillis();
        Entity[] allEntities = chunk.getEntities();
        if (allEntities.length == 0) return;

        List<Entity> mobs = new ArrayList<>(Math.min(allEntities.length, 64));
        List<Entity> items = new ArrayList<>(Math.min(allEntities.length, 32));

        ChunkStats stats = new ChunkStats();
        int validEntityCount = 0;

        for (Entity e : allEntities) {
            if (e == null || e instanceof Player) continue;
            if (!e.isValid() && !e.isDead()) continue;

            validEntityCount++;
            EntityType type = e.getType();

            if (e instanceof Item) {
                ItemStack stack = ((Item) e).getItemStack();
                if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                    items.add(e);
                    int amount = countItemStackAmount ? stack.getAmount() : 1;
                    stats.itemCounts.merge(stack.getType(), amount, Integer::sum);
                }
            } else if (e instanceof LivingEntity && !ignoredTypes.contains(type)) {
                mobs.add(e);
                stats.mobCounts.merge(type, 1, Integer::sum);
            }
        }

        String chunkKey = chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
        chunkStatsCache.put(chunkKey, new CachedChunkStats(stats, validEntityCount));
        if (chunkStatsCache.size() > 200) cleanOldestStatsCache();

        recordPerformance("Entity-Classification", System.currentTimeMillis() - classifyStart);

        long cleanupStart = System.currentTimeMillis();
        int removedMobs = processMobs(mobs);
        int removedItems = processItems(items);
        recordPerformance("Cleanup-Enforcement", System.currentTimeMillis() - cleanupStart);

        if (removedMobs + removedItems > 0) {
            debug("Chunk " + chunk.getX() + "," + chunk.getZ() + " cleaned: " + removedMobs + " mobs, " + removedItems + " items");
            sendCleanupReport(chunk, removedMobs, removedItems, stats);
        } else {
            checkChunkStatus(chunk, stats);
        }
    }

    private int processMobs(List<Entity> mobs) {
        if (mobs.isEmpty()) return 0;

        Map<EntityType, List<Entity>> grouped = new HashMap<>();
        for (Entity e : mobs) {
            if (!e.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                e.getPersistentDataContainer().set(SPAWN_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis());
            }
            grouped.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add(e);
        }

        int totalRemoved = 0;
        for (Map.Entry<EntityType, List<Entity>> entry : grouped.entrySet()) {
            int limit = customLimits.getOrDefault(entry.getKey().name(), defaultLimit);
            int removed = enforceLimit(entry.getValue(), limit, false);
            if (removed > 0) {
                removalStats.merge(entry.getKey(), (long)removed, Long::sum);
                totalRemoved += removed;
            }
        }
        return totalRemoved;
    }

    private int processItems(List<Entity> items) {
        if (items.isEmpty()) return 0;
        for(Entity e : items) {
            if (!e.getPersistentDataContainer().has(SPAWN_TIME_KEY, PersistentDataType.LONG)) {
                e.getPersistentDataContainer().set(SPAWN_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis());
            }
        }
        return enforceLimit(items, itemLimit, true);
    }

    private int enforceLimit(List<Entity> entities, int limit, boolean isItem) {
        if (entities.isEmpty()) return 0;

        int currentSize = 0;
        if (isItem && countItemStackAmount) {
            for (Entity e : entities) {
                if (e instanceof Item) currentSize += ((Item)e).getItemStack().getAmount();
                else currentSize++;
            }
        } else {
            currentSize = entities.size();
        }

        if (currentSize <= limit) return 0;

        List<EntityTimeWrapper> wrappers = new ArrayList<>(entities.size());

        for (Entity e : entities) {
            boolean protectedEntity = !isItem && isEntityProtected(e);
            boolean canRemove = !protectedEntity;

            if (cleanProtectedIfOverLimit && protectedEntity) {
                canRemove = true;
            } else if (ignoredTypes.contains(e.getType())) {
                canRemove = false;
            }

            if (canRemove) {
                int weight = 1;
                if (isItem && countItemStackAmount && e instanceof Item) {
                    weight = ((Item)e).getItemStack().getAmount();
                }
                wrappers.add(new EntityTimeWrapper(e, SPAWN_TIME_KEY, protectedEntity, weight));
            }
        }

        Collections.sort(wrappers);

        int actualRemovedCount = 0;
        int removedWeight = 0;
        List<Location> particleLocs = new ArrayList<>();

        int weightToRemove = currentSize - limit;

        for (EntityTimeWrapper wrapper : wrappers) {
            int needed = weightToRemove - removedWeight;
            if (needed <= 0) break;

            Entity entity = wrapper.entity;
            if (entity.isValid()) {
                boolean fullRemove = true;

                if (isItem && countItemStackAmount && entity instanceof Item) {
                    Item itemEntity = (Item) entity;
                    ItemStack stack = itemEntity.getItemStack();
                    int amount = stack.getAmount();

                    if (amount > needed) {
                        stack.setAmount(amount - needed);
                        itemEntity.setItemStack(stack);
                        removedWeight += needed;
                        fullRemove = false;
                    }
                }

                if (fullRemove) {
                    if (particleLocs.size() < 10) particleLocs.add(entity.getLocation());
                    entity.remove();
                    actualRemovedCount++;
                    removedWeight += wrapper.weight;
                }
            }
        }

        if (!particleLocs.isEmpty()) spawnRemovalParticles(particleLocs);
        debug("Enforced limit: removed entities/amount (" + removedWeight + ")");
        return (isItem && countItemStackAmount) ? removedWeight : actualRemovedCount;
    }

    private static class CacheEntry {
        final boolean canRemove;
        final long timestamp;
        CacheEntry(boolean r) { this.canRemove = r; this.timestamp = System.currentTimeMillis(); }
    }

    private static final long CACHE_TTL = 30000;

    private boolean canRemoveEntityCached(Entity entity) {
        if (entity == null || !entity.isValid()) return false;

        int entityId = entity.getEntityId();
        CacheEntry entry = protectionCache.get(entityId);

        if (entry != null && (System.currentTimeMillis() - entry.timestamp < CACHE_TTL)) {
            return entry.canRemove;
        }

        boolean result = canRemoveEntity(entity);
        protectionCache.put(entityId, new CacheEntry(result));
        return result;
    }

    private boolean canRemoveEntity(Entity entity) {
        if (entity instanceof Player) return false;
        if (ignoredTypes.contains(entity.getType())) return false;
        return !isEntityProtected(entity);
    }

    private boolean isEntityProtected(Entity entity) {
        if (protectNamedEntities) {
            String name = entity.getCustomName();
            if (name != null && !name.isEmpty()) return true;
        }
        if (entity instanceof LivingEntity) {
            return isLivingEntityProtected((LivingEntity) entity);
        }
        return false;
    }

    private boolean isLivingEntityProtected(LivingEntity living) {
        if (protectLeashedEntities && living.isLeashed()) return true;
        if (protectBossEntities && living instanceof Boss) return true;

        if (protectTamedAnimals && living instanceof Tameable) {
            if (((Tameable) living).isTamed()) return true;
        }

        if (protectEquippedEntities && hasSpecialEquipment(living)) return true;
        return hasPlayerPassengers(living);
    }

    private boolean hasSpecialEquipment(LivingEntity entity) {
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) return false;
        if (hasItem(eq.getItemInMainHand()) || hasItem(eq.getItemInOffHand())) return true;
        for (ItemStack armor : eq.getArmorContents()) {
            if (hasItem(armor)) return true;
        }
        return false;
    }

    private boolean hasItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }

    private boolean hasPlayerPassengers(LivingEntity entity) {
        List<Entity> passengers = entity.getPassengers();
        if (passengers.isEmpty()) return false;

        for (Entity p : passengers) {
            if (p instanceof Player) return true;
            if (p instanceof LivingEntity && hasPlayerPassengers((LivingEntity)p)) return true;
        }
        return false;
    }

    private void processPlayerChunkGroup(Player player, Set<Chunk> globalProcessed) {
        if (!player.isOnline()) return;
        Chunk center = player.getLocation().getChunk();
        World world = center.getWorld();

        List<Chunk> group = new ArrayList<>();
        int radius = chunkCheckRadius;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (world.isChunkLoaded(center.getX() + x, center.getZ() + z)) {
                    Chunk c = world.getChunkAt(center.getX() + x, center.getZ() + z);
                    if (globalProcessed != null && !globalProcessed.add(c)) continue;
                    group.add(c);
                }
            }
        }

        if (!group.isEmpty()) processChunkGroupLogic(group);
    }

    private void processChunkGroupLogic(List<Chunk> chunks) {
        Map<EntityType, List<Entity>> groupMobs = new HashMap<>();
        List<Entity> groupItems = new ArrayList<>();

        for (Chunk c : chunks) {
            for (Entity e : c.getEntities()) {
                if (e instanceof LivingEntity && !ignoredTypes.contains(e.getType())) {
                    groupMobs.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add(e);
                } else if (e instanceof Item && !ignoredItems.contains(((Item)e).getItemStack().getType())) {
                    groupItems.add(e);
                }
            }
        }

        int removedMobs = 0;
        for (Map.Entry<EntityType, List<Entity>> entry : groupMobs.entrySet()) {
            int limit = (int)(getLimitFor(entry.getKey()) * chunkEntityMultiplier);
            removedMobs += enforceLimit(entry.getValue(), limit, false);
        }

        int itemLimitGroup = (int)(itemLimit * chunkItemMultiplier);
        int removedItems = enforceLimit(groupItems, itemLimitGroup, true);

        if (removedMobs + removedItems > 0) {
            sendGroupCleanupReport(chunks, removedMobs, removedItems);
        }
    }

    private boolean isChunkValid(Chunk chunk) {
        return chunk != null && chunk.isLoaded() && chunk.getWorld() != null;
    }

    private int getLimitFor(EntityType type) {
        return customLimits.getOrDefault(type.name(), defaultLimit);
    }

    private void spawnRemovalParticles(List<Location> locations) {
        for (Location loc : locations) {
            if (loc.getWorld() != null) {
                loc.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, loc, 1, 0.2, 0.2, 0.2, 0.0);
            }
        }
    }

    private void sendCleanupReport(Chunk chunk, int rm, int ri, ChunkStats currentStats) {
        if (cleanupReportScope == NotifyScope.NONE) return;

        int curMobs = currentStats.mobCounts.values().stream().mapToInt(i->i).sum();
        int curItems = currentStats.itemCounts.values().stream().mapToInt(i->i).sum();

        Map<String, String> params = new HashMap<>();
        params.put("mobs", String.valueOf(rm));
        params.put("items", String.valueOf(ri));
        params.put("x", String.valueOf(chunk.getX()));
        params.put("z", String.valueOf(chunk.getZ()));
        params.put("world", chunk.getWorld().getName());
        params.put("current_mobs", String.valueOf(curMobs));
        params.put("current_items", String.valueOf(curItems));

        String msg = replacePlaceholders(msgCleanupReport, params);
        if (consoleCleanupReport) {
            getLogger().info(ChatColor.stripColor(msg));
        }
        notifyNearby(chunk, msg, cleanupReportScope, opGlobalCleanupReport);
    }

    private void sendGroupCleanupReport(List<Chunk> chunks, int rm, int ri) {
        if (cleanupReportScope == NotifyScope.NONE || chunks.isEmpty()) return;
        Chunk c = chunks.get(0);
        Map<String, String> params = new HashMap<>();
        params.put("mobs", String.valueOf(rm));
        params.put("items", String.valueOf(ri));
        params.put("world", c.getWorld().getName());
        params.put("x", String.valueOf(c.getX()));
        params.put("z", String.valueOf(c.getZ()));

        String msg = replacePlaceholders(msgGroupCleanupReport, params);
        if (consoleCleanupReport) {
            getLogger().info(ChatColor.stripColor(msg));
        }
        notifyNearby(c, msg, cleanupReportScope, opGlobalCleanupReport);
    }

    private void checkChunkStatus(Chunk chunk, ChunkStats stats) {
        if (overloadWarningScope == NotifyScope.NONE) return;
        stats.mobCounts.forEach((type, count) -> {
            int limit = customLimits.getOrDefault(type.name(), defaultLimit);
            if (count >= limit * thresholdRatio) sendTypeWarning(chunk, type.name(), count, limit);
        });

        int totalItems = stats.itemCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalItems >= itemLimit * thresholdRatio) sendTypeWarning(chunk, "Items", totalItems, itemLimit);
    }

    private void sendTypeWarning(Chunk chunk, String typeName, int current, int limit) {
        String chunkKey = typeName + ":" + chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(chunkKey, 0L) > notifyCooldown * 1000L) {
            Map<String, String> params = new HashMap<>();
            params.put("type", typeName);
            params.put("current", String.valueOf(current));
            params.put("max", String.valueOf(limit));
            params.put("chunkX", String.valueOf(chunk.getX()));
            params.put("chunkZ", String.valueOf(chunk.getZ()));
            params.put("world", chunk.getWorld().getName());
            notifyNearby(chunk, replacePlaceholders(msgPreOverload, params), overloadWarningScope, opGlobalOverloadWarning);
            lastNotifyTimes.put(chunkKey, System.currentTimeMillis());
        }
    }

    private void notifyNearby(Chunk chunk, String msg, NotifyScope scope, boolean globalOp) {
        if (scope == NotifyScope.NONE) return;

        Location center = chunk.getBlock(8, 64, 8).getLocation();
        double rSq = notificationRadius * notificationRadius;
        World chunkWorld = chunk.getWorld();

        for (Player p : getServer().getOnlinePlayers()) {
            boolean isOp = p.hasPermission("chunklimiter.notify");
            boolean sent = false;

            if (isOp && globalOp) {
                p.sendMessage(msg);
                sent = true;
            }

            if (!sent) {
                if (p.getWorld().equals(chunkWorld)) {
                    if (p.getLocation().distanceSquared(center) <= rSq) {
                        if (scope == NotifyScope.ALL || (scope == NotifyScope.OP && isOp)) {
                            p.sendMessage(msg);
                        }
                    }
                }
            }
        }
    }

    private String replacePlaceholders(String template, Map<String, String> params) {
        StringBuffer sb = new StringBuffer();
        Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        while (m.find()) {
            String val = params.get(m.group(1));
            m.appendReplacement(sb, val != null ? Matcher.quoteReplacement(val) : m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void cleanOldestStatsCache() {
        chunkStatsCache.clear();
    }

    private void setupMaintenanceTask() {
        Runnable task = () -> {
            cleanupCache();
            cleanupStats();
        };

        if (IS_FOLIA) getServer().getGlobalRegionScheduler().runAtFixedRate(this, t -> task.run(), 1200, 1200);
        else getServer().getScheduler().runTaskTimerAsynchronously(this, task, 1200, 1200);
    }

    private void cleanupCache() {
        long expire = System.currentTimeMillis() - CACHE_TTL;
        protectionCache.entrySet().removeIf(e -> e.getValue().timestamp < expire);

        long notifyExpire = System.currentTimeMillis() - (notifyCooldown * 2000L);
        lastNotifyTimes.entrySet().removeIf(e -> e.getValue() < notifyExpire);
    }

    private void cleanupStats() {
        if (performanceStats.size() > 15) performanceStats.clear();
    }

    private static class ChunkStats {
        final Map<EntityType, Integer> mobCounts = new HashMap<>();
        final Map<Material, Integer> itemCounts = new HashMap<>();
    }

    private static class ProtectionStats {
        int namedCount = 0;
        int leashedCount = 0;
        int tamedCount = 0;
        int equippedCount = 0;
        int bossCount = 0;
        int totalProtected = 0;

        void addEntity(Entity entity, ChunkEntityLimiter plugin) {
            boolean isProtected = false;
            if (plugin.protectNamedEntities && entity.getCustomName() != null && !entity.getCustomName().trim().isEmpty()) {
                namedCount++;
                isProtected = true;
            }

            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                if (plugin.protectLeashedEntities && living.isLeashed()) {
                    leashedCount++;
                    isProtected = true;
                }
                if (plugin.protectTamedAnimals && living instanceof Tameable && ((Tameable) living).isTamed()) {
                    tamedCount++;
                    isProtected = true;
                }
                if (plugin.protectEquippedEntities && plugin.hasSpecialEquipment(living)) {
                    equippedCount++;
                    isProtected = true;
                }
                if (plugin.protectBossEntities && living instanceof Boss) {
                    bossCount++;
                    isProtected = true;
                }
            }

            if (isProtected) {
                totalProtected++;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("chunklimit")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender); return true;
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
                if (!sender.hasPermission("chunklimiter.notify")) {
                    sender.sendMessage(msgNoPermission);
                    return true;
                }

                if (args.length == 1) {
                    Map<String, String> params = new HashMap<>();
                    params.put("report", cleanupReportScope.name());
                    params.put("warning", overloadWarningScope.name());
                    sender.sendMessage(replacePlaceholders(msgNotifyStatus, params));
                    return true;
                }

                if (args.length >= 3) {
                    String type = args[1].toLowerCase();
                    String scopeStr = args[2].toUpperCase();
                    NotifyScope newScope;
                    try {
                        newScope = NotifyScope.valueOf(scopeStr);
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(ChatColor.RED + "Invalid scope. Use: NONE, OP, ALL");
                        return true;
                    }

                    if (type.equals("report")) {
                        cleanupReportScope = newScope;
                    } else if (type.equals("warning")) {
                        overloadWarningScope = newScope;
                    } else {
                        sender.sendMessage(ChatColor.RED + "Usage: /cl notify [report|warning] [none|op|all]");
                        return true;
                    }

                    Map<String, String> params = new HashMap<>();
                    params.put("type", type);
                    params.put("scope", newScope.name());
                    sender.sendMessage(replacePlaceholders(msgScopeSet, params));
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /cl notify [report|warning] [none|op|all]");
                }
                return true;

            case "performance":
                if (!sender.hasPermission("chunklimiter.performance")) {
                    sender.sendMessage(msgNoPermission);
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
                    performanceStats.clear();
                    sender.sendMessage(msgPerfReset);
                    return true;
                }
                sender.sendMessage(msgPerfHeader);
                performanceStats.forEach((key, stats) -> {
                    String displayName = key;
                    if (key.equals("Phase1-SingleChunks")) displayName = msgPerfPhase1;
                    else if (key.equals("Phase2-GroupChunks")) displayName = msgPerfPhase2;
                    else if (key.equals("Total-Cleanup")) displayName = msgPerfTotal;
                    else if (key.equals("Entity-Classification")) displayName = msgPerfClassify;
                    else if (key.equals("Cleanup-Enforcement")) displayName = msgPerfCleanup;

                    sender.sendMessage(ChatColor.GRAY + displayName + ": " + ChatColor.GREEN + String.format("%.2f", stats.getAverage()) + "ms");
                });
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    private void showChunkStats(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        showSingleChunkStats(player, chunk);
        if (chunkCheckRadius > 0) {
            showGroupChunkStats(player);
        }
    }

    private void showGroupChunkStats(Player player) {
        Location loc = player.getLocation();
        Chunk center = loc.getChunk();
        World world = center.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        List<Chunk> chunkGroup = new ArrayList<>();
        for (int x = -chunkCheckRadius; x <= chunkCheckRadius; x++) {
            for (int z = -chunkCheckRadius; z <= chunkCheckRadius; z++) {
                if (world.isChunkLoaded(center.getX() + x, center.getZ() + z)) {
                    chunkGroup.add(world.getChunkAt(center.getX() + x, center.getZ() + z));
                }
            }
        }

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
                    protectionStats.addEntity(entity, this);
                } else if (entity instanceof Item) {
                    Item item = (Item) entity;
                    ItemStack itemStack = item.getItemStack();
                    if (itemStack != null) {
                        Material type = itemStack.getType();
                        if (ignoredItems.contains(type)) ignoredItemCount++;
                        else {
                            int amount = countItemStackAmount ? itemStack.getAmount() : 1;
                            totalItemCount += amount;
                        }
                    }
                }
            }
        }

        String header = currentLang.equals("zh") ?
                String.format("&6==== 区块组合统计 (半径: %d, 共%d个区块) ====", chunkCheckRadius, chunkGroup.size()) :
                String.format("&6==== Group Chunk Stats(Radius: %d, %d chunks) ====", chunkCheckRadius, chunkGroup.size());
        player.sendMessage(parseMessage(header));

        if (!totalMobs.isEmpty()) {
            player.sendMessage(parseMessage(msgMobStats));
            totalMobs.entrySet().stream().filter(e -> !ignoredTypes.contains(e.getKey())).forEach(entry -> {
                EntityType type = entry.getKey();
                int count = entry.getValue();
                int groupLimit = (int) Math.ceil(getLimitFor(type) * chunkEntityMultiplier);
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

        if (protectionStats.totalProtected > 0) {
            player.sendMessage(msgProtectedStats);
            sendProtectionStats(player, protectionStats);
        }

        int totalMobCount = totalMobs.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, String> totalParams = new HashMap<>();
        totalParams.put("total_mobs", String.valueOf(totalMobCount));
        totalParams.put("total_items", String.valueOf(totalItemCount + ignoredItemCount));
        player.sendMessage(replacePlaceholders(msgTotalStats, totalParams));
    }

    private void showSingleChunkStats(Player player, Chunk chunk) {
        World world = chunk.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        Map<EntityType, Long> allMobCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof LivingEntity && !(e instanceof Player))
                .peek(e -> protectionStats.addEntity(e, this))
                .collect(Collectors.groupingBy(Entity::getType, Collectors.counting()));

        long totalItems = 0;
        Map<Material, Long> allItemCounts = new HashMap<>();

        for (Entity e : chunk.getEntities()) {
            if (e instanceof Item) {
                ItemStack stack = ((Item) e).getItemStack();
                if (stack != null && stack.getType() != Material.AIR) {
                    long amount = countItemStackAmount ? stack.getAmount() : 1;
                    allItemCounts.merge(stack.getType(), amount, Long::sum);
                    if (!ignoredItems.contains(stack.getType())) {
                        totalItems += amount;
                    }
                }
            }
        }

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
                params.put("limit", isIgnored ? (currentLang.equals("zh") ? "无限制" : "Unlimited") : String.valueOf(limit));
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
                params.put("limit", isIgnored ? (currentLang.equals("zh") ? "无限制" : "Unlimited") : String.valueOf(limit));
                player.sendMessage(replacePlaceholders(msgItemStatsLine, params));
            });
        }

        if (protectionStats.totalProtected > 0) {
            player.sendMessage(msgProtectedStats);
            sendProtectionStats(player, protectionStats);
        }

        long totalMobs = allMobCounts.values().stream().mapToLong(Long::longValue).sum();

        Map<String, String> totalParams = new HashMap<>(baseParams);
        totalParams.put("total_mobs", String.valueOf(totalMobs));
        totalParams.put("total_items", String.valueOf(totalItems));
        player.sendMessage(replacePlaceholders(msgTotalStats, totalParams));
    }

    private void sendProtectionStats(Player player, ProtectionStats stats) {
        if (stats.namedCount > 0) player.sendMessage(replacePlaceholders(msgProtectedNamed, Collections.singletonMap("count", String.valueOf(stats.namedCount))));
        if (stats.leashedCount > 0) player.sendMessage(replacePlaceholders(msgProtectedLeashed, Collections.singletonMap("count", String.valueOf(stats.leashedCount))));
        if (stats.tamedCount > 0) player.sendMessage(replacePlaceholders(msgProtectedTamed, Collections.singletonMap("count", String.valueOf(stats.tamedCount))));
        if (stats.equippedCount > 0) player.sendMessage(replacePlaceholders(msgProtectedEquipped, Collections.singletonMap("count", String.valueOf(stats.equippedCount))));
        if (stats.bossCount > 0) player.sendMessage(replacePlaceholders(msgProtectedBoss, Collections.singletonMap("count", String.valueOf(stats.bossCount))));
        player.sendMessage(replacePlaceholders(msgProtectedTotal, Collections.singletonMap("count", String.valueOf(stats.totalProtected))));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "ChunkLimiter Help:");
        sender.sendMessage(ChatColor.GREEN + "/cl reload");
        sender.sendMessage(ChatColor.GREEN + "/cl stats");
        sender.sendMessage(ChatColor.GREEN + "/cl notify [report|warning] [none|op|all]");
        sender.sendMessage(ChatColor.GREEN + "/cl performance [reset]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("reload", "stats", "notify", "help", "performance");
        if (args.length == 2 && args[0].equalsIgnoreCase("notify")) return Arrays.asList("report", "warning");
        if (args.length == 3 && args[0].equalsIgnoreCase("notify")) return Arrays.asList("none", "op", "all");
        if (args.length == 2 && args[0].equalsIgnoreCase("performance")) return Collections.singletonList("reset");
        return Collections.emptyList();
    }

    @Override
    public void onDisable() {
        if (IS_FOLIA) {
            try { getServer().getGlobalRegionScheduler().cancelTasks(this); } catch (Exception ignored) {}
        } else {
            getServer().getScheduler().cancelTasks(this);
        }
        protectionCache.clear();
        chunkStatsCache.clear();
        removalStats.clear();
    }
}