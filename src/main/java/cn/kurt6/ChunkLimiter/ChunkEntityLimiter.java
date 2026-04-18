package cn.kurt6.ChunkLimiter;

import cn.kurt6.ChunkLimiter.bStats.Metrics;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    private final Map<String, PerformanceStats> performanceStats = new ConcurrentHashMap<>();
    private boolean performanceMonitoring = false;
    private Metrics metrics;
    private enum NotifyScope { NONE, OP, ALL }

    private static class EntityTimeWrapper implements Comparable<EntityTimeWrapper> {
        final Entity entity;
        final long spawnTime;
        final boolean isProtected;
        final int weight;

        EntityTimeWrapper(Entity entity, long spawnTime, boolean isProtected, int weight) {
            this.entity = entity;
            this.spawnTime = spawnTime;
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

    private <K> void incrementIntCount(Map<K, Integer> counts, K key, int delta) {
        Integer current = counts.get(key);
        counts.put(key, current == null ? delta : current + delta);
    }

    private <K> void incrementLongCount(Map<K, Long> counts, K key, long delta) {
        Long current = counts.get(key);
        counts.put(key, current == null ? delta : current + delta);
    }

    private List<Entity> getOrCreateEntityGroup(Map<EntityType, List<Entity>> groups, EntityType type) {
        List<Entity> group = groups.get(type);
        if (group == null) {
            group = new ArrayList<>();
            groups.put(type, group);
        }
        return group;
    }

    private static class PerformanceStats {
        private final java.util.concurrent.atomic.LongAdder totalTime = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder count = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.AtomicLong lastTime = new java.util.concurrent.atomic.AtomicLong();

        public void addValue(long value) {
            totalTime.add(value);
            count.increment();
            lastTime.set(value);
        }

        public double getAverageMillis() {
            long c = count.sum();
            return c > 0 ? (double) totalTime.sum() / c / 1_000_000.0 : 0;
        }

        public double getLastMillis() {
            return lastTime.get() / 1_000_000.0;
        }
    }

    private static final class ChunkGroupCenter {
        final World world;
        final int chunkX;
        final int chunkZ;

        ChunkGroupCenter(World world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class ChunkGroupBatch {
        final List<ChunkGroupCenter> centers;

        ChunkGroupBatch(List<ChunkGroupCenter> centers) {
            this.centers = centers;
        }
    }

    private static final class GroupChunkScan {
        final Map<EntityType, Integer> mobCounts = new EnumMap<>(EntityType.class);
        final Map<EntityType, List<GroupEntityRef>> mobCandidates = new EnumMap<>(EntityType.class);
        final List<GroupEntityRef> itemCandidates = new ArrayList<>();
        int itemCount;
    }

    private static final class GroupEntityRef implements Comparable<GroupEntityRef> {
        final Entity entity;
        final UUID entityId;
        final World world;
        final int chunkX;
        final int chunkZ;
        final long spawnTime;
        final boolean protectedEntity;
        int weight;

        GroupEntityRef(Entity entity, World world, int chunkX, int chunkZ, long spawnTime, boolean protectedEntity, int weight) {
            this.entity = entity;
            this.entityId = entity.getUniqueId();
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.spawnTime = spawnTime;
            this.protectedEntity = protectedEntity;
            this.weight = weight;
        }

        @Override
        public int compareTo(GroupEntityRef other) {
            if (this.protectedEntity != other.protectedEntity) {
                return this.protectedEntity ? 1 : -1;
            }
            return Long.compare(this.spawnTime, other.spawnTime);
        }
    }

    private static final class GroupRemovalAction {
        final GroupEntityRef ref;
        final int removeWeight;
        final boolean fullRemove;

        GroupRemovalAction(GroupEntityRef ref, int removeWeight, boolean fullRemove) {
            this.ref = ref;
            this.removeWeight = removeWeight;
            this.fullRemove = fullRemove;
        }
    }

    private int defaultLimit = 100;
    private int itemLimit = 300;
    private int checkInterval = 600;
    private final Set<EntityType> ignoredTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Material> ignoredItems = ConcurrentHashMap.newKeySet();
    private volatile Map<EntityType, Integer> customLimitsByType = Collections.emptyMap();
    private volatile int chunkCheckRadius = 0;
    private volatile double chunkEntityMultiplier = 1.0;
    private volatile double chunkItemMultiplier = 1.0;
    private volatile double notificationRadius = 128.0;
    private volatile boolean debugMode = false;

    private String msgReloadSuccess, msgNoPermission, msgPlayerOnly, msgChunkHeader;
    private String msgMobStats, msgMobStatsLine, msgItemStatsLine, msgTotalStats, msgItemStats;
    private String msgCleanupReport, msgPreOverload;
    private String msgScopeSet, msgNotifyStatus;
    private String msgPerfPhase1, msgPerfPhase2, msgPerfPhase2Pure, msgPerfPhase2Wait, msgPerfTotal, msgPerfClassify, msgPerfCleanup;
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

    private final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(\\w+)%");
    private final Map<EntityType, Long> removalStats = new ConcurrentHashMap<>();
    private volatile Map<String, GroupChunkScan> phase2ChunkCache = Collections.emptyMap();
    private final java.util.concurrent.atomic.AtomicLong phase2PureTimeNanos = new java.util.concurrent.atomic.AtomicLong();
    private final Map<UUID, ChunkGroupCenter> playerChunkCenters = new ConcurrentHashMap<>();
    private final Set<UUID> pendingPhase2EntityRemovals = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

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
        metrics = new Metrics(this, pluginId);

        saveDefaultConfig();
        initLanguages();
        if (!reloadConfiguration()) {
            getLogger().severe("Failed to load configuration, disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        warmPlayerChunkCenters();
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
        en.put("perf-phase2-pure", "Phase2-Pure");
        en.put("perf-phase2-wait", "Phase2-Wait");
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
        zh.put("perf-phase2-pure", "阶段2-纯处理");
        zh.put("perf-phase2-wait", "阶段2-调度等待");
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
    private final AtomicBoolean reloadInProgress = new AtomicBoolean(false);

    private boolean reloadConfiguration() {
        if (!reloadInProgress.compareAndSet(false, true)) return false;
        try {
            synchronized (configLock) {
                reloadConfig();
                loadSettings();
                loadMessages();
            }
            getLogger().info("Configuration reloaded");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Reload failed", e);
            return false;
        } finally {
            reloadInProgress.set(false);
        }
    }

    private void loadSettings() {
        ConfigurationSection config = getConfig();
        currentLang = config.getString("settings.language", "en").toLowerCase();
        if (!LANGUAGES.containsKey(currentLang)) currentLang = "en";

        ConfigurationSection limits = config.getConfigurationSection("entity-limits");
        if (limits == null) {
            getLogger().warning("Missing 'entity-limits' section in config.yml, using defaults.");
            limits = config.createSection("entity-limits");
        }
        defaultLimit = Math.max(0, limits.getInt("default-limit", 100));
        itemLimit = Math.max(0, limits.getInt("item-limit", 300));
        checkInterval = Math.max(1, limits.getInt("check-interval-ticks", 600));
        chunkCheckRadius = Math.max(0, limits.getInt("chunk-check-radius", 0));
        chunkEntityMultiplier = Math.max(0.1, limits.getDouble("chunk_entity_multiplier", 1.0));
        chunkItemMultiplier = Math.max(0.1, limits.getDouble("chunk_item_multiplier", 1.0));
        countItemStackAmount = limits.getBoolean("count-item-stack-amount", false);

        loadEnumSet(ignoredTypes, limits.getStringList("ignored-types"), EntityType.class);
        loadEnumSet(ignoredItems, limits.getStringList("ignored-items"), Material.class);

        ConfigurationSection custom = limits.getConfigurationSection("custom-limits");
        Map<EntityType, Integer> parsedCustomLimits = new EnumMap<>(EntityType.class);
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(key.toUpperCase());
                    parsedCustomLimits.put(type, Math.max(0, custom.getInt(key)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        customLimitsByType = parsedCustomLimits;

        ConfigurationSection settings = config.getConfigurationSection("settings");
        if (settings == null) {
            getLogger().warning("Missing 'settings' section in config.yml, using defaults.");
            settings = config.createSection("settings");
        }

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
        notifyCooldown = Math.max(0, settings.getInt("notify-cooldown", 10));
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
        msgPerfPhase2Pure = parseMessage(messages.get("perf-phase2-pure"));
        msgPerfPhase2Wait = parseMessage(messages.get("perf-phase2-wait"));
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

    private String formatPerformanceLine(String key, PerformanceStats stats) {
        String displayName = key;
        if (key.equals("Phase1-SingleChunks")) displayName = msgPerfPhase1;
        else if (key.equals("Phase2-GroupChunks")) displayName = msgPerfPhase2;
        else if (key.equals("Phase2-GroupChunks-Pure")) displayName = msgPerfPhase2Pure;
        else if (key.equals("Phase2-GroupChunks-Wait")) displayName = msgPerfPhase2Wait;
        else if (key.equals("Total-Cleanup")) displayName = msgPerfTotal;
        else if (key.equals("Entity-Classification")) displayName = msgPerfClassify;
        else if (key.equals("Cleanup-Enforcement")) displayName = msgPerfCleanup;

        return ChatColor.GRAY + displayName + ": "
                + ChatColor.GREEN + String.format(Locale.ROOT, "%.3f", stats.getAverageMillis()) + "ms"
                + ChatColor.DARK_GRAY + " | "
                + ChatColor.YELLOW + "last "
                + String.format(Locale.ROOT, "%.3f", stats.getLastMillis()) + "ms";
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

    private void restartScheduledTasks() {
        if (IS_FOLIA) {
            try {
                getServer().getGlobalRegionScheduler().cancelTasks(this);
            } catch (Exception ignored) {}
        } else {
            getServer().getScheduler().cancelTasks(this);
        }
        setupCleanupTask();
        setupMaintenanceTask();
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
        long totalStart = System.nanoTime();
        phase2PureTimeNanos.set(0);
        if (IS_FOLIA && cleanAllLoadedChunks && chunkCheckRadius > 0) {
            phase2ChunkCache = new ConcurrentHashMap<>();
        } else {
            phase2ChunkCache = Collections.emptyMap();
        }
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
                processPhase2Folia(totalStart);
            } else {
                recordPerformance("Total-Cleanup", System.nanoTime() - totalStart);
            }
            return;
        }

        AtomicInteger totalChunks = new AtomicInteger(0);

        Map<World, List<Chunk>> worldChunks = new HashMap<>();
        for (World world : getServer().getWorlds()) {
            Chunk[] chunks = world.getLoadedChunks();
            if (chunks.length > 0) {
                List<Chunk> chunkList = new ArrayList<>(chunks.length);
                for (Chunk c : chunks) {
                    if (c != null && c.isLoaded()) chunkList.add(c);
                }
                if (!chunkList.isEmpty()) {
                    worldChunks.put(world, chunkList);
                    totalChunks.addAndGet(chunkList.size());
                }
            }
        }

        if (totalChunks.get() == 0) {
            recordPerformance("Total-Cleanup", System.nanoTime() - totalStart);
            return;
        }

        long phase1Start = System.nanoTime();
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
                            finishPhase1Folia(phase1Start, totalStart);
                        }
                    }
                });
            }
        });

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            if (phase1Completed.compareAndSet(false, true)) {
                finishPhase1Folia(phase1Start, totalStart);
            }
        }, 1200L);
    }

    private void finishPhase1Folia(long phase1Start, long totalStart) {
        long duration = System.nanoTime() - phase1Start;
        lastPhase1Duration = duration;
        recordPerformance("Phase1-SingleChunks", duration);

        if (chunkCheckRadius > 0) {
            processPhase2Folia(totalStart);
        } else {
            recordPerformance("Total-Cleanup", duration);
        }
    }

    private void processChunksWithPaper(long totalStart) {
        long phase1Start = System.nanoTime();
        Set<String> processedCenters = (chunkCheckRadius > 0) ? new HashSet<>() : null;

        if (cleanAllLoadedChunks) {
            for (World world : getServer().getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    if (chunk != null && chunk.isLoaded()) {
                        processSingleChunk(chunk);
                    }
                }
            }
        }

        long phase1End = System.nanoTime();
        lastPhase1Duration = phase1End - phase1Start;
        recordPerformance("Phase1-SingleChunks", lastPhase1Duration);

        if (chunkCheckRadius > 0) {
            long phase2Start = System.nanoTime();
            for (Player player : getServer().getOnlinePlayers()) {
                processPlayerChunkGroup(player, processedCenters);
            }
            long phase2End = System.nanoTime();
            lastPhase2Duration = phase2End - phase2Start;
            recordPerformance("Phase2-GroupChunks", lastPhase2Duration);
            recordPerformance("Total-Cleanup", (phase1End - phase1Start) + lastPhase2Duration);
        } else {
            recordPerformance("Total-Cleanup", lastPhase1Duration);
        }
    }

    private void processPhase2Folia(long totalStart) {
        long phase2Start = System.nanoTime();
        Collection<? extends Player> players = getServer().getOnlinePlayers();
        if (players.isEmpty()) {
            lastPhase2Duration = 0;
            recordPerformance("Phase2-GroupChunks", 0);
            recordPerformance("Phase2-GroupChunks-Pure", 0);
            recordPerformance("Phase2-GroupChunks-Wait", 0);
            recordPerformance("Total-Cleanup", System.nanoTime() - totalStart);
            phase2ChunkCache = Collections.emptyMap();
            return;
        }

        Map<String, ChunkGroupCenter> centers = new ConcurrentHashMap<>();
        List<Player> missingPlayers = new ArrayList<>();
        for (Player player : players) {
            if (!player.isOnline()) continue;
            ChunkGroupCenter cached = playerChunkCenters.get(player.getUniqueId());
            if (cached != null) {
                centers.putIfAbsent(getChunkCacheKey(cached.world, cached.chunkX, cached.chunkZ), cached);
            } else {
                missingPlayers.add(player);
            }
        }

        if (missingPlayers.isEmpty()) {
            startPhase2Folia(new ArrayList<>(centers.values()), phase2Start, totalStart);
            return;
        }

        AtomicInteger pendingPlayers = new AtomicInteger(0);

        for (Player player : missingPlayers) {
            pendingPlayers.incrementAndGet();

            Runnable complete = () -> {
                if (pendingPlayers.decrementAndGet() == 0) {
                    startPhase2Folia(new ArrayList<>(centers.values()), phase2Start, totalStart);
                }
            };

            if (player.getScheduler().run(this, task -> {
                try {
                    if (!player.isOnline()) return;
                    Location location = player.getLocation();
                    World world = player.getWorld();
                    int chunkX = location.getBlockX() >> 4;
                    int chunkZ = location.getBlockZ() >> 4;
                    ChunkGroupCenter center = new ChunkGroupCenter(world, chunkX, chunkZ);
                    playerChunkCenters.put(player.getUniqueId(), center);
                    centers.putIfAbsent(getChunkCacheKey(world, chunkX, chunkZ), center);
                } finally {
                    complete.run();
                }
            }, complete) == null) {
                complete.run();
            }
        }

        if (pendingPlayers.get() == 0) {
            startPhase2Folia(new ArrayList<>(centers.values()), phase2Start, totalStart);
        }
    }

    private void startPhase2Folia(List<ChunkGroupCenter> centers, long phase2Start, long totalStart) {
        if (centers.isEmpty()) {
            lastPhase2Duration = 0;
            recordPerformance("Phase2-GroupChunks", 0);
            recordPerformance("Phase2-GroupChunks-Pure", 0);
            recordPerformance("Phase2-GroupChunks-Wait", 0);
            recordPerformance("Total-Cleanup", System.nanoTime() - totalStart);
            phase2ChunkCache = Collections.emptyMap();
            return;
        }

        AtomicInteger processed = new AtomicInteger(0);
        int total = centers.size();
        boolean lightweightMode = shouldUseLightweightPhase2Path(centers);

        for (ChunkGroupCenter center : centers) {
            if (!phase2ChunkCache.isEmpty()) {
                processChunkGroupFoliaFromCache(center, lightweightMode, () -> checkPhase2Finish(processed, total, phase2Start, totalStart));
            } else {
                processChunkGroupFolia(center, lightweightMode, () -> checkPhase2Finish(processed, total, phase2Start, totalStart));
            }
        }
    }

    private boolean shouldUseLightweightPhase2Path(List<ChunkGroupCenter> centers) {
        return centers.size() <= 4;
    }

    private void checkPhase2Finish(AtomicInteger processed, int total, long phase2Start, long totalStart) {
        if (processed.incrementAndGet() >= total) {
            long p2Duration = System.nanoTime() - phase2Start;
            lastPhase2Duration = p2Duration;
            recordPerformance("Phase2-GroupChunks", p2Duration);
            long pure = Math.min(phase2PureTimeNanos.get(), p2Duration);
            recordPerformance("Phase2-GroupChunks-Pure", pure);
            recordPerformance("Phase2-GroupChunks-Wait", Math.max(0L, p2Duration - pure));
            recordPerformance("Total-Cleanup", System.nanoTime() - totalStart);
            phase2ChunkCache = Collections.emptyMap();
        }
    }

    private void processSingleChunk(Chunk chunk) {
        if (!isChunkValid(chunk)) return;

        long classifyStart = System.nanoTime();
        Entity[] allEntities = chunk.getEntities();
        if (allEntities.length == 0) return;

        if (!IS_FOLIA) {
            processSingleChunkPaper(chunk, allEntities, classifyStart);
            return;
        }

        boolean captureGroupScan = IS_FOLIA && cleanAllLoadedChunks && chunkCheckRadius > 0 && !phase2ChunkCache.isEmpty();
        GroupChunkScan groupScan = captureGroupScan ? new GroupChunkScan() : null;
        long groupNow = captureGroupScan ? System.currentTimeMillis() : 0L;
        ChunkStats stats = new ChunkStats();

        for (Entity e : allEntities) {
            if (e == null || e instanceof Player) continue;
            if (!e.isValid() || e.isDead()) continue;

            EntityType type = e.getType();

            if (e instanceof Item) {
                ItemStack stack = ((Item) e).getItemStack();
                if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                    int amount = countItemStackAmount ? stack.getAmount() : 1;
                    incrementIntCount(stats.itemCounts, stack.getType(), amount);
                    if (captureGroupScan) {
                        groupScan.itemCount += amount;
                        groupScan.itemCandidates.add(new GroupEntityRef(e, chunk.getWorld(), chunk.getX(), chunk.getZ(), getOrCreateSpawnTime(e, groupNow), false, amount));
                    }
                }
            } else if (e instanceof LivingEntity && !ignoredTypes.contains(type)) {
                incrementIntCount(stats.mobCounts, type, 1);
                if (captureGroupScan) {
                    incrementIntCount(groupScan.mobCounts, type, 1);
                    boolean protectedEntity = isEntityProtected(e);
                    if (!protectedEntity || cleanProtectedIfOverLimit) {
                        getOrCreateGroupEntityRefs(groupScan.mobCandidates, type)
                                .add(new GroupEntityRef(e, chunk.getWorld(), chunk.getX(), chunk.getZ(), getOrCreateSpawnTime(e, groupNow), protectedEntity, 1));
                    }
                }
            }
        }

        if (captureGroupScan && (!groupScan.mobCounts.isEmpty() || groupScan.itemCount > 0)) {
            phase2ChunkCache.put(getChunkCacheKey(chunk.getWorld(), chunk.getX(), chunk.getZ()), groupScan);
        }

        recordPerformance("Entity-Classification", System.nanoTime() - classifyStart);

        EnumSet<EntityType> mobsOverLimit = EnumSet.noneOf(EntityType.class);
        for (Map.Entry<EntityType, Integer> entry : stats.mobCounts.entrySet()) {
            if (entry.getValue() > getLimitFor(entry.getKey())) {
                mobsOverLimit.add(entry.getKey());
            }
        }

        int totalItems = 0;
        for (int value : stats.itemCounts.values()) {
            totalItems += value;
        }
        boolean itemsOverLimit = totalItems > itemLimit;

        if (mobsOverLimit.isEmpty() && !itemsOverLimit) {
            checkChunkStatus(chunk, stats);
            return;
        }

        Map<EntityType, List<Entity>> mobsByType = mobsOverLimit.isEmpty() ? Collections.emptyMap() : new EnumMap<>(EntityType.class);
        List<Entity> items = itemsOverLimit ? new ArrayList<>(Math.min(allEntities.length, 32)) : Collections.emptyList();

        for (Entity e : allEntities) {
            if (e == null || e instanceof Player) continue;
            if (!e.isValid() || e.isDead()) continue;

            if (e instanceof Item) {
                if (!itemsOverLimit) continue;
                ItemStack stack = ((Item) e).getItemStack();
                if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                    items.add(e);
                }
                continue;
            }

            EntityType type = e.getType();
            if (e instanceof LivingEntity && mobsOverLimit.contains(type)) {
                getOrCreateEntityGroup(mobsByType, type).add(e);
            }
        }

        long cleanupStart = System.nanoTime();
        int removedMobs = processMobs(mobsByType);
        int removedItems = processItems(items);
        recordPerformance("Cleanup-Enforcement", System.nanoTime() - cleanupStart);

        if (removedMobs + removedItems > 0) {
            debug("Chunk " + chunk.getX() + "," + chunk.getZ() + " cleaned: " + removedMobs + " mobs, " + removedItems + " items");
            sendCleanupReport(chunk, removedMobs, removedItems, stats);
        }
    }

    private void processSingleChunkPaper(Chunk chunk, Entity[] allEntities, long classifyStart) {
        Map<EntityType, List<Entity>> mobsByType = new EnumMap<>(EntityType.class);
        List<Entity> items = new ArrayList<>(Math.min(allEntities.length, 32));
        ChunkStats stats = new ChunkStats();

        for (Entity e : allEntities) {
            if (e == null || e instanceof Player) continue;
            if (!e.isValid() || e.isDead()) continue;

            EntityType type = e.getType();
            if (e instanceof Item) {
                ItemStack stack = ((Item) e).getItemStack();
                if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                    items.add(e);
                    incrementIntCount(stats.itemCounts, stack.getType(), countItemStackAmount ? stack.getAmount() : 1);
                }
            } else if (e instanceof LivingEntity && !ignoredTypes.contains(type)) {
                getOrCreateEntityGroup(mobsByType, type).add(e);
                incrementIntCount(stats.mobCounts, type, 1);
            }
        }

        recordPerformance("Entity-Classification", System.nanoTime() - classifyStart);

        long cleanupStart = System.nanoTime();
        int removedMobs = processMobs(mobsByType);
        int removedItems = processItems(items);
        recordPerformance("Cleanup-Enforcement", System.nanoTime() - cleanupStart);

        if (removedMobs + removedItems > 0) {
            debug("Chunk " + chunk.getX() + "," + chunk.getZ() + " cleaned: " + removedMobs + " mobs, " + removedItems + " items");
            sendCleanupReport(chunk, removedMobs, removedItems, stats);
        } else {
            checkChunkStatus(chunk, stats);
        }
    }

    private int processMobs(Map<EntityType, List<Entity>> grouped) {
        if (grouped.isEmpty()) return 0;

        int totalRemoved = 0;
        for (Map.Entry<EntityType, List<Entity>> entry : grouped.entrySet()) {
            int limit = getLimitFor(entry.getKey());
            int removed = enforceLimit(entry.getValue(), limit, false);
            if (removed > 0) {
                incrementLongCount(removalStats, entry.getKey(), removed);
                totalRemoved += removed;
            }
        }
        return totalRemoved;
    }

    private int processItems(List<Entity> items) {
        if (items.isEmpty()) return 0;
        return enforceLimit(items, itemLimit, true);
    }

    private long getOrCreateSpawnTime(Entity entity, long now) {
        Long spawnTime = entity.getPersistentDataContainer().get(SPAWN_TIME_KEY, PersistentDataType.LONG);
        if (spawnTime != null) {
            return spawnTime;
        }
        entity.getPersistentDataContainer().set(SPAWN_TIME_KEY, PersistentDataType.LONG, now);
        return now;
    }

    private List<EntityTimeWrapper> selectOldestCandidates(List<EntityTimeWrapper> wrappers, int maxToRemove) {
        if (wrappers.size() <= maxToRemove) {
            wrappers.sort(null);
            return wrappers;
        }

        PriorityQueue<EntityTimeWrapper> selected = new PriorityQueue<>(maxToRemove, Collections.reverseOrder());
        for (EntityTimeWrapper wrapper : wrappers) {
            if (selected.size() < maxToRemove) {
                selected.offer(wrapper);
            } else if (wrapper.compareTo(selected.peek()) < 0) {
                selected.poll();
                selected.offer(wrapper);
            }
        }

        List<EntityTimeWrapper> result = new ArrayList<>(selected);
        result.sort(null);
        return result;
    }

    private int enforceLimit(List<Entity> entities, int limit, boolean isItem) {
        if (entities.isEmpty()) return 0;

        int currentSize = 0;
        if (isItem && countItemStackAmount) {
            for (Entity e : entities) {
                if (e instanceof Item) {
                    ItemStack s = ((Item) e).getItemStack();
                    currentSize += (s != null) ? s.getAmount() : 1;
                } else {
                    currentSize++;
                }
            }
        } else {
            currentSize = entities.size();
        }

        if (currentSize <= limit) return 0;

        List<EntityTimeWrapper> wrappers = new ArrayList<>(entities.size());
        long now = System.currentTimeMillis();

        for (Entity e : entities) {
            boolean protectedEntity = !isItem && isEntityProtected(e);
            boolean canRemove = !protectedEntity;

            if (cleanProtectedIfOverLimit && protectedEntity) {
                canRemove = true;
            } else if (!isItem && ignoredTypes.contains(e.getType())) {
                canRemove = false;
            }

            if (canRemove) {
                int weight = 1;
                if (isItem && countItemStackAmount && e instanceof Item) {
                    ItemStack s = ((Item) e).getItemStack();
                    weight = (s != null) ? s.getAmount() : 1;
                }
                wrappers.add(new EntityTimeWrapper(e, getOrCreateSpawnTime(e, now), protectedEntity, weight));
            }
        }

        if (wrappers.isEmpty()) return 0;
        int weightToRemove = currentSize - limit;
        boolean uniformWeightRemoval = !isItem || !countItemStackAmount;
        if (uniformWeightRemoval) {
            int maxToRemove = Math.min(weightToRemove, wrappers.size());
            wrappers = selectOldestCandidates(wrappers, maxToRemove);
        } else if (wrappers.size() > 1) {
            Collections.sort(wrappers);
        }

        int actualRemovedCount = 0;
        int removedWeight = 0;

        for (EntityTimeWrapper wrapper : wrappers) {
            int needed = weightToRemove - removedWeight;
            if (needed <= 0) break;

            Entity entity = wrapper.entity;
            if (entity.isValid()) {
                boolean fullRemove = true;

                if (isItem && countItemStackAmount && entity instanceof Item) {
                    Item itemEntity = (Item) entity;
                    ItemStack stack = itemEntity.getItemStack();
                    if (stack != null) {
                        int amount = stack.getAmount();
                        if (amount > needed) {
                            stack.setAmount(amount - needed);
                            itemEntity.setItemStack(stack);
                            removedWeight += needed;
                            fullRemove = false;
                        }
                    }
                }

                if (fullRemove) {
                    entity.remove();
                    actualRemovedCount++;
                    removedWeight += wrapper.weight;
                }
            }
        }

        debug("Enforced limit: removed entities/amount (" + removedWeight + ")");
        return (isItem && countItemStackAmount) ? removedWeight : actualRemovedCount;
    }

    private boolean isEntityProtected(Entity entity) {
        if (protectNamedEntities) {
            String name = entity.getCustomName();
            if (name != null && !name.trim().isEmpty()) return true;
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

    private boolean chunkGroupsOverlap(ChunkGroupCenter left, ChunkGroupCenter right) {
        return left.world.equals(right.world)
                && Math.abs(left.chunkX - right.chunkX) <= chunkCheckRadius * 2
                && Math.abs(left.chunkZ - right.chunkZ) <= chunkCheckRadius * 2;
    }

    private List<ChunkGroupBatch> buildChunkGroupBatches(Collection<? extends Player> players) {
        if (players.isEmpty()) return Collections.emptyList();

        Map<String, ChunkGroupCenter> uniqueCenters = new LinkedHashMap<>();
        for (Player player : players) {
            if (!player.isOnline()) continue;
            Location location = player.getLocation();
            Chunk chunk = location.getChunk();
            World world = chunk.getWorld();
            String key = world.getName() + ":" + chunk.getX() + ":" + chunk.getZ();
            if (!uniqueCenters.containsKey(key)) {
                uniqueCenters.put(key, new ChunkGroupCenter(world, chunk.getX(), chunk.getZ()));
            }
        }

        List<ChunkGroupCenter> centers = new ArrayList<>(uniqueCenters.values());
        if (centers.isEmpty()) return Collections.emptyList();

        List<ChunkGroupBatch> batches = new ArrayList<>();
        boolean[] visited = new boolean[centers.size()];

        for (int i = 0; i < centers.size(); i++) {
            if (visited[i]) continue;

            List<ChunkGroupCenter> batchCenters = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            visited[i] = true;

            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                ChunkGroupCenter current = centers.get(index);
                batchCenters.add(current);

                for (int j = 0; j < centers.size(); j++) {
                    if (!visited[j] && chunkGroupsOverlap(current, centers.get(j))) {
                        visited[j] = true;
                        queue.addLast(j);
                    }
                }
            }

            batches.add(new ChunkGroupBatch(batchCenters));
        }

        return batches;
    }

    private void processPlayerChunkGroup(Player player, Set<String> processedCenters) {
        if (!player.isOnline()) return;
        Chunk center = player.getLocation().getChunk();
        if (processedCenters != null && !processedCenters.add(getChunkKey(center))) return;
        World world = center.getWorld();

        int radius = chunkCheckRadius;
        int groupCapacity = (radius * 2 + 1) * (radius * 2 + 1);
        List<Chunk> group = new ArrayList<>(groupCapacity);

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (world.isChunkLoaded(center.getX() + x, center.getZ() + z)) {
                    Chunk c = world.getChunkAt(center.getX() + x, center.getZ() + z);
                    group.add(c);
                }
            }
        }

        if (!group.isEmpty()) processChunkGroupLogic(group);
    }

    private void processChunkGroupFolia(ChunkGroupCenter center, boolean lightweightMode, Runnable onComplete) {
        scheduleRegionalTask(center.world, center.chunkX, center.chunkZ, () -> {
            if (getServer().isOwnedByCurrentRegion(center.world, center.chunkX, center.chunkZ, chunkCheckRadius)) {
                processOwnedChunkGroupFolia(center, lightweightMode, onComplete);
                return;
            }
            processChunkGroupFoliaFallback(center, lightweightMode, onComplete);
        });
    }

    private void processChunkGroupFoliaFallback(ChunkGroupCenter center, boolean lightweightMode, Runnable onComplete) {
        int radius = chunkCheckRadius;
        int diameter = radius * 2 + 1;
        int totalChunks = diameter * diameter;
        Map<String, GroupChunkScan> scans = new ConcurrentHashMap<>();
        AtomicInteger completed = new AtomicInteger(0);

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                final int chunkX = center.chunkX + x;
                final int chunkZ = center.chunkZ + z;
                scheduleRegionalTask(center.world, chunkX, chunkZ, () -> {
                    try {
                        if (center.world.isChunkLoaded(chunkX, chunkZ)) {
                            Chunk chunk = center.world.getChunkAt(chunkX, chunkZ);
                            GroupChunkScan scan = scanGroupChunk(chunk);
                            if (scan != null) {
                                scans.put(getChunkCoordKey(chunkX, chunkZ), scan);
                            }
                        }
                    } finally {
                        if (completed.incrementAndGet() >= totalChunks) {
                            finishChunkGroupFolia(center, scans.values(), false, lightweightMode, onComplete);
                        }
                    }
                });
            }
        }
    }

    private void processChunkGroupFoliaFromCache(ChunkGroupCenter center, boolean lightweightMode, Runnable onComplete) {
        if (lightweightMode) {
            int radius = chunkCheckRadius;
            long collectStart = System.nanoTime();
            List<GroupChunkScan> scans = new ArrayList<>();
            Map<String, GroupChunkScan> cache = phase2ChunkCache;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    GroupChunkScan scan = cache.get(getChunkCacheKey(center.world, center.chunkX + x, center.chunkZ + z));
                    if (scan != null) {
                        scans.add(scan);
                    }
                }
            }
            phase2PureTimeNanos.addAndGet(System.nanoTime() - collectStart);
            finishChunkGroupFolia(center, scans, false, true, onComplete);
            return;
        }

        scheduleRegionalTask(center.world, center.chunkX, center.chunkZ, () -> {
            if (getServer().isOwnedByCurrentRegion(center.world, center.chunkX, center.chunkZ, chunkCheckRadius)) {
                int radius = chunkCheckRadius;
                long collectStart = System.nanoTime();
                List<GroupChunkScan> scans = new ArrayList<>();
                Map<String, GroupChunkScan> cache = phase2ChunkCache;
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        GroupChunkScan scan = cache.get(getChunkCacheKey(center.world, center.chunkX + x, center.chunkZ + z));
                        if (scan != null) {
                            scans.add(scan);
                        }
                    }
                }
                phase2PureTimeNanos.addAndGet(System.nanoTime() - collectStart);
                finishChunkGroupFolia(center, scans, true, lightweightMode, onComplete);
                return;
            }

            int radius = chunkCheckRadius;
            long collectStart = System.nanoTime();
            List<GroupChunkScan> scans = new ArrayList<>();
            Map<String, GroupChunkScan> cache = phase2ChunkCache;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    GroupChunkScan scan = cache.get(getChunkCacheKey(center.world, center.chunkX + x, center.chunkZ + z));
                    if (scan != null) {
                        scans.add(scan);
                    }
                }
            }
            phase2PureTimeNanos.addAndGet(System.nanoTime() - collectStart);
            finishChunkGroupFolia(center, scans, false, lightweightMode, onComplete);
        });
    }

    private void processOwnedChunkGroupFolia(ChunkGroupCenter center, boolean lightweightMode, Runnable onComplete) {
        int radius = chunkCheckRadius;
        List<GroupChunkScan> scans = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = center.chunkX + x;
                int chunkZ = center.chunkZ + z;
                if (!center.world.isChunkLoaded(chunkX, chunkZ)) continue;
                Chunk chunk = center.world.getChunkAt(chunkX, chunkZ);
                GroupChunkScan scan = scanGroupChunk(chunk);
                if (scan != null) {
                    scans.add(scan);
                }
            }
        }
        finishChunkGroupFolia(center, scans, true, lightweightMode, onComplete);
    }

    private GroupChunkScan scanGroupChunk(Chunk chunk) {
        long pureStart = System.nanoTime();
        try {
        Entity[] entities = chunk.getEntities();
        if (entities.length == 0) return null;

        GroupChunkScan scan = new GroupChunkScan();
        long now = System.currentTimeMillis();
        for (Entity entity : entities) {
            if (entity == null || !entity.isValid() || entity.isDead() || entity instanceof Player) continue;

            if (entity instanceof Item) {
                ItemStack stack = ((Item) entity).getItemStack();
                if (stack == null || stack.getType() == Material.AIR || ignoredItems.contains(stack.getType())) continue;

                int weight = countItemStackAmount ? stack.getAmount() : 1;
                scan.itemCount += weight;
                scan.itemCandidates.add(new GroupEntityRef(entity, chunk.getWorld(), chunk.getX(), chunk.getZ(), getOrCreateSpawnTime(entity, now), false, weight));
                continue;
            }

            if (!(entity instanceof LivingEntity)) continue;

            EntityType type = entity.getType();
            if (ignoredTypes.contains(type)) continue;

            incrementIntCount(scan.mobCounts, type, 1);

            boolean protectedEntity = isEntityProtected(entity);
            if (protectedEntity && !cleanProtectedIfOverLimit) continue;

            List<GroupEntityRef> candidates = getOrCreateGroupEntityRefs(scan.mobCandidates, type);
            candidates.add(new GroupEntityRef(entity, chunk.getWorld(), chunk.getX(), chunk.getZ(), getOrCreateSpawnTime(entity, now), protectedEntity, 1));
        }

        return scan;
        } finally {
            phase2PureTimeNanos.addAndGet(System.nanoTime() - pureStart);
        }
    }

    private List<GroupEntityRef> getOrCreateGroupEntityRefs(Map<EntityType, List<GroupEntityRef>> groups, EntityType type) {
        List<GroupEntityRef> group = groups.get(type);
        if (group == null) {
            group = new ArrayList<>();
            groups.put(type, group);
        }
        return group;
    }

    private void finishChunkGroupFolia(ChunkGroupCenter center, Collection<GroupChunkScan> scans, boolean directOwned, boolean lightweightMode, Runnable onComplete) {
        long pureStart = System.nanoTime();
        EnumSet<EntityType> overLimitMobTypes = EnumSet.noneOf(EntityType.class);
        Map<EntityType, Integer> groupMobCounts = new EnumMap<>(EntityType.class);
        int groupItemCount = 0;

        for (GroupChunkScan scan : scans) {
            groupItemCount += scan.itemCount;
            for (Map.Entry<EntityType, Integer> entry : scan.mobCounts.entrySet()) {
                incrementIntCount(groupMobCounts, entry.getKey(), entry.getValue());
            }
        }

        for (Map.Entry<EntityType, Integer> entry : groupMobCounts.entrySet()) {
            if (entry.getValue() > getGroupedLimitFor(entry.getKey())) {
                overLimitMobTypes.add(entry.getKey());
            }
        }

        int itemLimitGroup = getGroupedItemLimit();
        boolean itemsOverLimit = groupItemCount > itemLimitGroup;
        if (overLimitMobTypes.isEmpty() && !itemsOverLimit) {
            phase2PureTimeNanos.addAndGet(System.nanoTime() - pureStart);
            onComplete.run();
            return;
        }

        Map<String, List<GroupRemovalAction>> actionsByChunk = new ConcurrentHashMap<>();
        int removedMobs = 0;

        for (EntityType type : overLimitMobTypes) {
            List<GroupEntityRef> candidates = new ArrayList<>();
            for (GroupChunkScan scan : scans) {
                List<GroupEntityRef> scanCandidates = scan.mobCandidates.get(type);
                if (scanCandidates != null && !scanCandidates.isEmpty()) {
                    candidates.addAll(scanCandidates);
                }
            }
            removedMobs += buildGroupRemovalActions(candidates, getGroupedLimitFor(type), false, actionsByChunk);
        }

        int removedItems = 0;
        if (itemsOverLimit) {
            List<GroupEntityRef> candidates = new ArrayList<>();
            for (GroupChunkScan scan : scans) {
                if (!scan.itemCandidates.isEmpty()) {
                    candidates.addAll(scan.itemCandidates);
                }
            }
            removedItems = buildGroupRemovalActions(candidates, itemLimitGroup, true, actionsByChunk);
        }

        if (removedMobs + removedItems <= 0) {
            phase2PureTimeNanos.addAndGet(System.nanoTime() - pureStart);
            onComplete.run();
            return;
        }

        phase2PureTimeNanos.addAndGet(System.nanoTime() - pureStart);
        executeGroupRemovalActions(actionsByChunk, center, removedMobs, removedItems, directOwned, lightweightMode, onComplete);
    }

    private int buildGroupRemovalActions(List<GroupEntityRef> candidates, int limit, boolean isItem, Map<String, List<GroupRemovalAction>> actionsByChunk) {
        if (candidates.isEmpty()) return 0;

        int currentSize = 0;
        for (GroupEntityRef candidate : candidates) {
            if (pendingPhase2EntityRemovals.contains(candidate.entityId)) continue;
            currentSize += (isItem && countItemStackAmount) ? candidate.weight : 1;
        }
        if (currentSize <= limit) return 0;

        int weightToRemove = currentSize - limit;
        boolean uniformWeightRemoval = !isItem || !countItemStackAmount;
        if (uniformWeightRemoval) {
            int maxToRemove = Math.min(weightToRemove, candidates.size());
            candidates = selectOldestGroupEntityRefs(candidates, maxToRemove);
        } else if (candidates.size() > 1) {
            Collections.sort(candidates);
        }

        int removed = 0;
        for (GroupEntityRef candidate : candidates) {
            if (pendingPhase2EntityRemovals.contains(candidate.entityId)) continue;
            int needed = weightToRemove - removed;
            if (needed <= 0) break;

            int removeWeight = uniformWeightRemoval ? 1 : Math.min(candidate.weight, needed);
            boolean fullRemove = removeWeight >= candidate.weight;
            String key = getChunkCoordKey(candidate.chunkX, candidate.chunkZ);
            actionsByChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new GroupRemovalAction(candidate, removeWeight, fullRemove));
            removed += removeWeight;
        }

        return removed;
    }

    private List<GroupEntityRef> selectOldestGroupEntityRefs(List<GroupEntityRef> refs, int maxToRemove) {
        if (refs.size() <= maxToRemove) {
            refs.sort(null);
            return refs;
        }

        PriorityQueue<GroupEntityRef> selected = new PriorityQueue<>(maxToRemove, Collections.reverseOrder());
        for (GroupEntityRef ref : refs) {
            if (selected.size() < maxToRemove) {
                selected.offer(ref);
            } else if (ref.compareTo(selected.peek()) < 0) {
                selected.poll();
                selected.offer(ref);
            }
        }

        List<GroupEntityRef> result = new ArrayList<>(selected);
        result.sort(null);
        return result;
    }

    private void executeGroupRemovalActions(Map<String, List<GroupRemovalAction>> actionsByChunk, ChunkGroupCenter center, int removedMobs, int removedItems, boolean directOwned, boolean lightweightMode, Runnable onComplete) {
        if (actionsByChunk.isEmpty()) {
            onComplete.run();
            return;
        }

        if (directOwned) {
            long pureStart = System.nanoTime();
            try {
                for (List<GroupRemovalAction> actions : actionsByChunk.values()) {
                    for (GroupRemovalAction action : actions) {
                        pendingPhase2EntityRemovals.add(action.ref.entityId);
                    }
                }
                for (List<GroupRemovalAction> actions : actionsByChunk.values()) {
                    for (GroupRemovalAction action : actions) {
                        try {
                            applyGroupRemovalAction(action);
                        } finally {
                            pendingPhase2EntityRemovals.remove(action.ref.entityId);
                        }
                    }
                }
            } finally {
                phase2PureTimeNanos.addAndGet(System.nanoTime() - pureStart);
            }
            try {
                sendGroupCleanupReport(center.world, center.chunkX, center.chunkZ, removedMobs, removedItems);
            } finally {
                onComplete.run();
            }
            return;
        }

        AtomicInteger completed = new AtomicInteger(0);
        int total = actionsByChunk.size();
        for (List<GroupRemovalAction> actions : actionsByChunk.values()) {
            for (GroupRemovalAction action : actions) {
                pendingPhase2EntityRemovals.add(action.ref.entityId);
            }
        }

        for (List<GroupRemovalAction> actions : actionsByChunk.values()) {
            GroupRemovalAction first = actions.get(0);
            scheduleRegionalTask(first.ref.world, first.ref.chunkX, first.ref.chunkZ, () -> {
                long pureStart = System.nanoTime();
                try {
                    for (GroupRemovalAction action : actions) {
                        applyGroupRemovalAction(action);
                    }
                } finally {
                    for (GroupRemovalAction action : actions) {
                        pendingPhase2EntityRemovals.remove(action.ref.entityId);
                    }
                    if (!lightweightMode) {
                        phase2PureTimeNanos.addAndGet(System.nanoTime() - pureStart);
                    }
                    if (completed.incrementAndGet() >= total) {
                        try {
                            sendGroupCleanupReport(center.world, center.chunkX, center.chunkZ, removedMobs, removedItems);
                        } finally {
                            if (!lightweightMode) {
                                onComplete.run();
                            }
                        }
                    }
                }
            });
        }

        if (lightweightMode) {
            onComplete.run();
        }
    }

    private void applyGroupRemovalAction(GroupRemovalAction action) {
        Entity entity = action.ref.entity;
        if (!entity.isValid()) return;

        if (!action.fullRemove && entity instanceof Item) {
            Item itemEntity = (Item) entity;
            ItemStack stack = itemEntity.getItemStack();
            if (stack != null) {
                int amount = stack.getAmount();
                if (amount > action.removeWeight) {
                    stack.setAmount(amount - action.removeWeight);
                    itemEntity.setItemStack(stack);
                    return;
                }
            }
        }

        entity.remove();
    }

    private String getChunkCoordKey(int chunkX, int chunkZ) {
        return chunkX + ":" + chunkZ;
    }

    private String getChunkCacheKey(World world, int chunkX, int chunkZ) {
        return world.getName() + ":" + chunkX + ":" + chunkZ;
    }

    private void warmPlayerChunkCenters() {
        playerChunkCenters.clear();
        for (Player player : getServer().getOnlinePlayers()) {
            refreshPlayerChunkCenter(player);
        }
    }

    private void refreshPlayerChunkCenter(Player player) {
        if (!player.isOnline()) {
            playerChunkCenters.remove(player.getUniqueId());
            return;
        }

        if (IS_FOLIA) {
            player.getScheduler().run(this, task -> cachePlayerChunkCenter(player), null);
        } else {
            cachePlayerChunkCenter(player);
        }
    }

    private void cachePlayerChunkCenter(Player player) {
        if (!player.isOnline()) {
            playerChunkCenters.remove(player.getUniqueId());
            return;
        }
        Location location = player.getLocation();
        updatePlayerChunkCenter(player.getUniqueId(), player.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void updatePlayerChunkCenter(UUID playerId, World world, int chunkX, int chunkZ) {
        playerChunkCenters.put(playerId, new ChunkGroupCenter(world, chunkX, chunkZ));
    }

    private void removePlayerChunkCenter(UUID playerId) {
        playerChunkCenters.remove(playerId);
    }

    private void processChunkGroupBatch(ChunkGroupBatch batch, Runnable onComplete) {
        try {
            for (ChunkGroupCenter center : batch.centers) {
                int radius = chunkCheckRadius;
                int groupCapacity = (radius * 2 + 1) * (radius * 2 + 1);
                List<Chunk> group = new ArrayList<>(groupCapacity);

                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (center.world.isChunkLoaded(center.chunkX + x, center.chunkZ + z)) {
                            group.add(center.world.getChunkAt(center.chunkX + x, center.chunkZ + z));
                        }
                    }
                }

                if (!group.isEmpty()) {
                    processChunkGroupLogic(group);
                }
            }
        } finally {
            onComplete.run();
        }
    }

    private void processChunkGroupLogic(List<Chunk> chunks) {
        Map<EntityType, Integer> groupMobCounts = new EnumMap<>(EntityType.class);
        int groupItemCount = 0;

        for (Chunk c : chunks) {
            for (Entity e : c.getEntities()) {
                if (e == null || !e.isValid() || e.isDead()) continue;
                if (e instanceof LivingEntity && !(e instanceof Player) && !ignoredTypes.contains(e.getType())) {
                    incrementIntCount(groupMobCounts, e.getType(), 1);
                } else if (e instanceof Item) {
                    ItemStack stack = ((Item) e).getItemStack();
                    if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                        groupItemCount += countItemStackAmount ? stack.getAmount() : 1;
                    }
                }
            }
        }

        EnumSet<EntityType> overLimitMobTypes = EnumSet.noneOf(EntityType.class);
        for (Map.Entry<EntityType, Integer> entry : groupMobCounts.entrySet()) {
            if (entry.getValue() > getGroupedLimitFor(entry.getKey())) {
                overLimitMobTypes.add(entry.getKey());
            }
        }

        int itemLimitGroup = getGroupedItemLimit();
        boolean itemsOverLimit = groupItemCount > itemLimitGroup;
        if (overLimitMobTypes.isEmpty() && !itemsOverLimit) return;

        Map<EntityType, List<Entity>> groupMobs = new EnumMap<>(EntityType.class);
        List<Entity> groupItems = itemsOverLimit ? new ArrayList<>() : Collections.emptyList();

        for (Chunk c : chunks) {
            for (Entity e : c.getEntities()) {
                if (e == null || !e.isValid() || e.isDead()) continue;
                if (e instanceof LivingEntity && !(e instanceof Player) && overLimitMobTypes.contains(e.getType())) {
                    getOrCreateEntityGroup(groupMobs, e.getType()).add(e);
                } else if (itemsOverLimit && e instanceof Item) {
                    ItemStack stack = ((Item) e).getItemStack();
                    if (stack != null && stack.getType() != Material.AIR && !ignoredItems.contains(stack.getType())) {
                        groupItems.add(e);
                    }
                }
            }
        }

        int removedMobs = 0;
        for (Map.Entry<EntityType, List<Entity>> entry : groupMobs.entrySet()) {
            int limit = getGroupedLimitFor(entry.getKey());
            removedMobs += enforceLimit(entry.getValue(), limit, false);
        }

        int removedItems = itemsOverLimit ? enforceLimit(groupItems, itemLimitGroup, true) : 0;

        if (removedMobs + removedItems > 0) {
            sendGroupCleanupReport(chunks, removedMobs, removedItems);
        }
    }

    private boolean isChunkValid(Chunk chunk) {
        return chunk != null && chunk.isLoaded() && chunk.getWorld() != null;
    }

    private String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private int getLimitFor(EntityType type) {
        return customLimitsByType.getOrDefault(type, defaultLimit);
    }

    private int getGroupedLimitFor(EntityType type) {
        return (int) Math.ceil(getLimitFor(type) * chunkEntityMultiplier);
    }

    private int getGroupedItemLimit() {
        return (int) Math.ceil(itemLimit * chunkItemMultiplier);
    }

    private void sendCleanupReport(Chunk chunk, int rm, int ri, ChunkStats currentStats) {
        if (cleanupReportScope == NotifyScope.NONE) return;

        int curMobs = 0;
        for (int v : currentStats.mobCounts.values()) curMobs += v;
        int curItems = 0;
        for (int v : currentStats.itemCounts.values()) curItems += v;
        curMobs = Math.max(0, curMobs - rm);
        curItems = Math.max(0, curItems - ri);

        String msg = replaceCleanupReportPlaceholders(msgCleanupReport, chunk, rm, ri, curMobs, curItems);
        if (consoleCleanupReport) {
            getLogger().info(ChatColor.stripColor(msg));
        }
        notifyNearby(chunk, msg, cleanupReportScope, opGlobalCleanupReport);
    }

    private void sendGroupCleanupReport(List<Chunk> chunks, int rm, int ri) {
        if (cleanupReportScope == NotifyScope.NONE || chunks.isEmpty()) return;
        Chunk c = chunks.get(0);
        String msg = replaceGroupCleanupReportPlaceholders(msgGroupCleanupReport, c, rm, ri);
        if (consoleCleanupReport) {
            getLogger().info(ChatColor.stripColor(msg));
        }
        notifyNearby(c, msg, cleanupReportScope, opGlobalCleanupReport);
    }

    private void sendGroupCleanupReport(World world, int chunkX, int chunkZ, int rm, int ri) {
        if (cleanupReportScope == NotifyScope.NONE) return;

        String msg = replaceGroupCleanupReportPlaceholders(msgGroupCleanupReport, world, chunkX, chunkZ, rm, ri);
        if (consoleCleanupReport) {
            getLogger().info(ChatColor.stripColor(msg));
        }
        notifyNearby(world, chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0, msg, cleanupReportScope, opGlobalCleanupReport);
    }

    private void checkChunkStatus(Chunk chunk, ChunkStats stats) {
        if (overloadWarningScope == NotifyScope.NONE) return;
        stats.mobCounts.forEach((type, count) -> {
            int limit = getLimitFor(type);
            if (count >= limit * thresholdRatio) sendTypeWarning(chunk, type.name(), count, limit);
        });

        int totalItems = 0;
        for (int v : stats.itemCounts.values()) totalItems += v;
        if (totalItems >= itemLimit * thresholdRatio) sendTypeWarning(chunk, "Items", totalItems, itemLimit);
    }

    private void sendTypeWarning(Chunk chunk, String typeName, int current, int limit) {
        String warningMessage = replaceWarningPlaceholders(msgPreOverload, chunk, typeName, current, limit);

        if (notifyCooldown == 0) {
            notifyNearby(chunk, warningMessage, overloadWarningScope, opGlobalOverloadWarning);
            return;
        }

        String chunkKey = typeName + ":" + chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(chunkKey, 0L) > notifyCooldown * 1000L) {
            notifyNearby(chunk, warningMessage, overloadWarningScope, opGlobalOverloadWarning);
            lastNotifyTimes.put(chunkKey, System.currentTimeMillis());
        }
    }

    private void notifyNearby(Chunk chunk, String msg, NotifyScope scope, boolean globalOp) {
        notifyNearby(chunk.getWorld(), chunk.getX() * 16.0 + 8.0, chunk.getZ() * 16.0 + 8.0, msg, scope, globalOp);
    }

    private void notifyNearby(World world, double centerX, double centerZ, String msg, NotifyScope scope, boolean globalOp) {
        if (scope == NotifyScope.NONE) return;

        double rSq = notificationRadius * notificationRadius;
        for (Player p : getServer().getOnlinePlayers()) {
            if (!p.isOnline()) continue;

            if (IS_FOLIA) {
                p.getScheduler().run(this, task -> {
                    if (!p.isOnline()) return;
                    boolean isOp = p.hasPermission("chunklimiter.notify");
                    boolean sent = false;

                    if (isOp && globalOp) {
                        p.sendMessage(msg);
                        sent = true;
                    }

                    if (!sent && p.getWorld().equals(world)) {
                        Location playerLocation = p.getLocation();
                        double dx = playerLocation.getX() - centerX;
                        double dz = playerLocation.getZ() - centerZ;
                        if (dx * dx + dz * dz <= rSq) {
                            if (scope == NotifyScope.ALL || (scope == NotifyScope.OP && isOp)) {
                                p.sendMessage(msg);
                            }
                        }
                    }
                }, null);
                continue;
            }

            boolean isOp = p.hasPermission("chunklimiter.notify");
            boolean sent = false;

            if (isOp && globalOp) {
                p.sendMessage(msg);
                sent = true;
            }

            if (!sent && p.getWorld().equals(world)) {
                Location playerLocation = p.getLocation();
                double dx = playerLocation.getX() - centerX;
                double dz = playerLocation.getZ() - centerZ;
                if (dx * dx + dz * dz <= rSq) {
                    if (scope == NotifyScope.ALL || (scope == NotifyScope.OP && isOp)) {
                        p.sendMessage(msg);
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

    private String replaceCountPlaceholder(String template, int count) {
        return template.replace("%count%", String.valueOf(count));
    }

    private String replaceTypeStatsPlaceholders(String template, String type, long count, String limit) {
        return template
                .replace("%type%", type)
                .replace("%count%", String.valueOf(count))
                .replace("%limit%", limit);
    }

    private String replaceTotalStatsPlaceholders(String template, long totalMobs, long totalItems) {
        return template
                .replace("%total_mobs%", String.valueOf(totalMobs))
                .replace("%total_items%", String.valueOf(totalItems));
    }

    private String replaceCleanupReportPlaceholders(String template, Chunk chunk, int removedMobs, int removedItems, int currentMobs, int currentItems) {
        return template
                .replace("%mobs%", String.valueOf(removedMobs))
                .replace("%items%", String.valueOf(removedItems))
                .replace("%x%", String.valueOf(chunk.getX()))
                .replace("%z%", String.valueOf(chunk.getZ()))
                .replace("%world%", chunk.getWorld().getName())
                .replace("%current_mobs%", String.valueOf(currentMobs))
                .replace("%current_items%", String.valueOf(currentItems));
    }

    private String replaceGroupCleanupReportPlaceholders(String template, Chunk chunk, int removedMobs, int removedItems) {
        return template
                .replace("%mobs%", String.valueOf(removedMobs))
                .replace("%items%", String.valueOf(removedItems))
                .replace("%world%", chunk.getWorld().getName())
                .replace("%x%", String.valueOf(chunk.getX()))
                .replace("%z%", String.valueOf(chunk.getZ()));
    }

    private String replaceGroupCleanupReportPlaceholders(String template, World world, int chunkX, int chunkZ, int removedMobs, int removedItems) {
        return template
                .replace("%mobs%", String.valueOf(removedMobs))
                .replace("%items%", String.valueOf(removedItems))
                .replace("%world%", world.getName())
                .replace("%x%", String.valueOf(chunkX))
                .replace("%z%", String.valueOf(chunkZ));
    }

    private String replaceWarningPlaceholders(String template, Chunk chunk, String typeName, int current, int limit) {
        return template
                .replace("%type%", typeName)
                .replace("%current%", String.valueOf(current))
                .replace("%max%", String.valueOf(limit))
                .replace("%chunkX%", String.valueOf(chunk.getX()))
                .replace("%chunkZ%", String.valueOf(chunk.getZ()))
                .replace("%world%", chunk.getWorld().getName());
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
        if (notifyCooldown == 0) {
            if (!lastNotifyTimes.isEmpty()) {
                lastNotifyTimes.clear();
            }
            return;
        }
        long notifyExpire = System.currentTimeMillis() - (notifyCooldown * 1000L);
        lastNotifyTimes.entrySet().removeIf(e -> e.getValue() < notifyExpire);
    }

    private void cleanupStats() {
        if (performanceStats.size() > 15) performanceStats.clear();
    }

    private static class ChunkStats {
        final Map<EntityType, Integer> mobCounts = new EnumMap<>(EntityType.class);
        final Map<Material, Integer> itemCounts = new EnumMap<>(Material.class);
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
                if (reloadConfiguration()) {
                    warmPlayerChunkCenters();
                    restartScheduledTasks();
                    sender.sendMessage(msgReloadSuccess);
                } else {
                    sender.sendMessage(ChatColor.RED + "Configuration reload failed, check console logs");
                }
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
                if (!performanceMonitoring) {
                    sender.sendMessage(msgPerfDisabled);
                    return true;
                }
                sender.sendMessage(msgPerfHeader);
                if (performanceStats.isEmpty()) {
                    sender.sendMessage(msgPerfNoData);
                    return true;
                }
                String[] orderedKeys = {
                        "Total-Cleanup",
                        "Phase1-SingleChunks",
                        "Phase2-GroupChunks",
                        "Phase2-GroupChunks-Pure",
                        "Phase2-GroupChunks-Wait",
                        "Entity-Classification",
                        "Cleanup-Enforcement"
                };
                for (String key : orderedKeys) {
                    PerformanceStats stats = performanceStats.get(key);
                    if (stats == null) continue;
                    sender.sendMessage(formatPerformanceLine(key, stats));
                }
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

        int groupCapacity = (chunkCheckRadius * 2 + 1) * (chunkCheckRadius * 2 + 1);
        List<Chunk> chunkGroup = new ArrayList<>(groupCapacity);
        for (int x = -chunkCheckRadius; x <= chunkCheckRadius; x++) {
            for (int z = -chunkCheckRadius; z <= chunkCheckRadius; z++) {
                if (world.isChunkLoaded(center.getX() + x, center.getZ() + z)) {
                    chunkGroup.add(world.getChunkAt(center.getX() + x, center.getZ() + z));
                }
            }
        }

        Map<EntityType, Integer> totalMobs = new EnumMap<>(EntityType.class);
        Map<EntityType, Integer> ignoredMobCounts = new EnumMap<>(EntityType.class);
        int totalItemCount = 0;
        int ignoredItemCount = 0;

        for (Chunk chunk : chunkGroup) {
            for (Entity entity : chunk.getEntities()) {
                if (entity == null || !entity.isValid() || entity.isDead()) continue;
                if (entity instanceof Player) continue;
                if (entity instanceof LivingEntity) {
                    EntityType type = entity.getType();
                    incrementIntCount(totalMobs, type, 1);
                    if (ignoredTypes.contains(type)) {
                        incrementIntCount(ignoredMobCounts, type, 1);
                    }
                    protectionStats.addEntity(entity, this);
                } else if (entity instanceof Item) {
                    Item item = (Item) entity;
                    ItemStack itemStack = item.getItemStack();
                    if (itemStack != null && itemStack.getType() != Material.AIR) {
                        Material type = itemStack.getType();
                        int amount = countItemStackAmount ? itemStack.getAmount() : 1;
                        if (ignoredItems.contains(type)) {
                            ignoredItemCount += amount;
                        } else {
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
                int groupLimit = getGroupedLimitFor(type);
                player.sendMessage(replaceTypeStatsPlaceholders(msgMobStatsLine, type.name(), count, String.valueOf(groupLimit)));
            });
            ignoredMobCounts.forEach((type, count) -> {
                player.sendMessage(replaceTypeStatsPlaceholders(
                        msgMobStatsLine,
                        type.name() + " (ignored)",
                        count,
                        currentLang.equals("zh") ? "无限制" : "Unlimited"
                ));
            });
        }

        player.sendMessage(parseMessage(msgItemStats));
        if (totalItemCount > 0) {
            int groupItemLimit = getGroupedItemLimit();
            player.sendMessage(replaceTypeStatsPlaceholders(msgItemStatsLine, "Items", totalItemCount, String.valueOf(groupItemLimit)));
        }

        if (protectionStats.totalProtected > 0) {
            player.sendMessage(msgProtectedStats);
            sendProtectionStats(player, protectionStats);
        }

        int totalMobCount = 0;
        for (int v : totalMobs.values()) totalMobCount += v;
        player.sendMessage(replaceTotalStatsPlaceholders(msgTotalStats, totalMobCount, totalItemCount + ignoredItemCount));
    }

    private void showSingleChunkStats(Player player, Chunk chunk) {
        World world = chunk.getWorld();
        ProtectionStats protectionStats = new ProtectionStats();

        Map<EntityType, Long> allMobCounts = new EnumMap<>(EntityType.class);
        long totalItems = 0;
        Map<Material, Long> allItemCounts = new EnumMap<>(Material.class);

        for (Entity e : chunk.getEntities()) {
            if (e == null || !e.isValid() || e.isDead()) continue;
            if (e instanceof Player) continue;
            if (e instanceof LivingEntity) {
                protectionStats.addEntity(e, this);
                incrementLongCount(allMobCounts, e.getType(), 1);
            } else if (e instanceof Item) {
                ItemStack stack = ((Item) e).getItemStack();
                if (stack != null && stack.getType() != Material.AIR) {
                    long amount = countItemStackAmount ? stack.getAmount() : 1;
                    incrementLongCount(allItemCounts, stack.getType(), amount);
                    totalItems += amount;
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
                int limit = isIgnored ? -1 : getLimitFor(type);
                player.sendMessage(replaceTypeStatsPlaceholders(
                        msgMobStatsLine,
                        type.name(),
                        count,
                        isIgnored ? (currentLang.equals("zh") ? "无限制" : "Unlimited") : String.valueOf(limit)
                ));
            });
        }

        if (!allItemCounts.isEmpty()) {
            player.sendMessage(replacePlaceholders(msgItemStats, baseParams));
            allItemCounts.forEach((material, count) -> {
                boolean isIgnored = ignoredItems.contains(material);
                int limit = isIgnored ? -1 : itemLimit;
                player.sendMessage(replaceTypeStatsPlaceholders(
                        msgItemStatsLine,
                        material.name(),
                        count,
                        isIgnored ? (currentLang.equals("zh") ? "无限制" : "Unlimited") : String.valueOf(limit)
                ));
            });
        }

        if (protectionStats.totalProtected > 0) {
            player.sendMessage(msgProtectedStats);
            sendProtectionStats(player, protectionStats);
        }

        long totalMobs = 0;
        for (long v : allMobCounts.values()) totalMobs += v;

        player.sendMessage(replaceTotalStatsPlaceholders(msgTotalStats, totalMobs, totalItems));
    }

    private void sendProtectionStats(Player player, ProtectionStats stats) {
        if (stats.namedCount > 0) player.sendMessage(replaceCountPlaceholder(msgProtectedNamed, stats.namedCount));
        if (stats.leashedCount > 0) player.sendMessage(replaceCountPlaceholder(msgProtectedLeashed, stats.leashedCount));
        if (stats.tamedCount > 0) player.sendMessage(replaceCountPlaceholder(msgProtectedTamed, stats.tamedCount));
        if (stats.equippedCount > 0) player.sendMessage(replaceCountPlaceholder(msgProtectedEquipped, stats.equippedCount));
        if (stats.bossCount > 0) player.sendMessage(replaceCountPlaceholder(msgProtectedBoss, stats.bossCount));
        player.sendMessage(replaceCountPlaceholder(msgProtectedTotal, stats.totalProtected));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "ChunkLimiter Help:");
        sender.sendMessage(ChatColor.GREEN + "/cl reload");
        sender.sendMessage(ChatColor.GREEN + "/cl stats");
        sender.sendMessage(ChatColor.GREEN + "/cl notify [report|warning] [none|op|all]");
        sender.sendMessage(ChatColor.GREEN + "/cl performance [reset]");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshPlayerChunkCenter(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayerChunkCenter(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        refreshPlayerChunkCenter(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Location location = event.getRespawnLocation();
        if (location != null) {
            updatePlayerChunkCenter(event.getPlayer().getUniqueId(), location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        } else {
            refreshPlayerChunkCenter(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        updatePlayerChunkCenter(event.getPlayer().getUniqueId(), to.getWorld(), to.getBlockX() >> 4, to.getBlockZ() >> 4);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null) return;
        if (from.getWorld() == to.getWorld()
                && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        updatePlayerChunkCenter(event.getPlayer().getUniqueId(), to.getWorld(), to.getBlockX() >> 4, to.getBlockZ() >> 4);
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
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        removalStats.clear();
        playerChunkCenters.clear();
        phase2ChunkCache = Collections.emptyMap();
    }
}
