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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChunkEntityLimiter extends JavaPlugin implements Listener {

    // 配置参数
    private int defaultLimit = 100;
    private int itemLimit = 300;
    private int checkInterval = 600;
    private final Set<EntityType> ignoredTypes = ConcurrentHashMap.newKeySet();
    private final Set<Material> ignoredItems = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> customLimits = new ConcurrentHashMap<>();

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
        en.put("chunk-info-header", "&6==== Chunk Entities &7(World: %world%) (%x%,%z%) ====");
        en.put("mob-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        en.put("pre-overload", "&cWarning! %type% in chunk %world% (%chunkX%,%chunkZ%) nearing limit: %current%/%max%");
        en.put("mob-stats", "&6[Mobs]");
        en.put("item-stats", "&6[Items]");
        en.put("item-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        en.put("cleanup-report", "&6[Cleanup] Cleaned %mobs% mobs & %items% items in %world% (%x%,%z%)\n  Mobs: &c%current_mobs%/%mob_limit% &7| Items: &b%current_items%/%item_limit%");
        en.put("notification-enabled", "&aEntity notifications enabled");
        en.put("notification-disabled", "&cEntity notifications disabled");
        en.put("total-stats", "&6Total: &c%total_mobs% mobs &6| &b%total_items% items");

        // 中文消息
        Map<String, String> zh = new HashMap<>();
        zh.put("reload-success", "&a配置已重新加载！");
        zh.put("no-permission", "&c你没有执行该命令的权限");
        zh.put("player-only", "&c该命令只能在游戏中执行");
        zh.put("chunk-info-header", "&6==== 区块实体统计 &7(世界: %world%) (%x%,%z%) ====");
        zh.put("mob-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        zh.put("pre-overload", "&c警告！区块 %world% (%chunkX%,%chunkZ%) 的 %type% 数量即将超限：%current%/%max%");
        zh.put("mob-stats", "&6[生物统计]");
        zh.put("item-stats", "&6[物品统计]");
        zh.put("item-stats-line", " &7%type%: &a%count%&7/&c%limit%");
        zh.put("cleanup-report", "&6[清理报告] 在 %world% (%x%,%z%) 清理了 %mobs% 生物和 %items% 物品\n  &c生物: %current_mobs%/%mob_limit% &7| &b物品: %current_items%/%item_limit%");
        zh.put("notification-enabled", "&a实体清理通知已启用");
        zh.put("notification-disabled", "&c实体清理通知已禁用");
        zh.put("total-stats", "&6总计: &c%total_mobs% 生物 &6| &b%total_items% 物品");

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
    }

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

    private final AtomicInteger chunkCounter = new AtomicInteger(0);

    private void processAllChunks() {
        List<Chunk> allChunks = new ArrayList<>();
        for (World world : getServer().getWorlds()) {
            allChunks.addAll(Arrays.asList(world.getLoadedChunks()));
        }

        if (IS_FOLIA) {
            allChunks.forEach(chunk ->
                    Bukkit.getRegionScheduler().run(this, chunk.getWorld(), chunk.getX(), chunk.getZ(),
                            task -> processChunk(chunk))
            );
        } else {
            // 分批处理：每批处理10个区块，间隔1 tick
            int batchSize = 10;
            for (int i = 0; i < allChunks.size(); i += batchSize) {
                List<Chunk> batch = allChunks.subList(i, Math.min(i + batchSize, allChunks.size()));
                Bukkit.getScheduler().runTaskLater(this, () ->
                        batch.forEach(this::processChunk), i / batchSize
                );
            }
        }
    }

    private void processChunk(Chunk chunk) {
        Map<EntityCategory, List<Entity>> entities = Arrays.stream(chunk.getEntities())
                .filter(e -> !(e instanceof Player))
                .collect(Collectors.groupingBy(this::classifyEntity));

        int removedMobs = processMobs(entities.getOrDefault(EntityCategory.MOB, Collections.emptyList()));
        int removedItems = processItems(entities.getOrDefault(EntityCategory.ITEM, Collections.emptyList()));

        sendCleanupReport(chunk, removedMobs, removedItems);
        checkChunkStatus(chunk);
    }

    private enum EntityCategory { MOB, ITEM, OTHER }

    private EntityCategory classifyEntity(Entity e) {
        if (e instanceof Item) {
            return EntityCategory.ITEM;
        } else if (e instanceof LivingEntity && !ignoredTypes.contains(e.getType())) {
            return EntityCategory.MOB;
        } else {
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

        // 使用优先队列维护最早生成的实体
        PriorityQueue<Entity> oldestQueue = new PriorityQueue<>(Comparator.comparingLong(e ->
                e.getPersistentDataContainer().getOrDefault(SPAWN_TIME_KEY, PersistentDataType.LONG, Long.MAX_VALUE)
        ));
        oldestQueue.addAll(entities);

        int toRemove = entities.size() - limit;
        for (int i = 0; i < toRemove; i++) {
            Entity e = oldestQueue.poll();
            if (e != null) {
                e.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, e.getLocation(), 5);
                e.remove();
            }
        }
        return toRemove;
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
        params.put("mob_limit", String.valueOf(defaultLimit));
        params.put("current_items", String.valueOf(currentItems));
        params.put("item_limit", String.valueOf(itemLimit));

        if (enableNotifications) {
            String message = replacePlaceholders(msgCleanupReport, params);

            // 发送到控制台（去除颜色代码）
            getLogger().info(ChatColor.stripColor(message));

            // 发送给在线OP
            Bukkit.getOnlinePlayers().stream()
                    .filter(Player::isOp)
                    .forEach(p -> p.sendMessage(message));
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
        ChunkStats stats = collectChunkStats(chunk);

        // 处理生物预警
        stats.mobCounts.forEach((type, count) -> {
            int limit = customLimits.getOrDefault(type.name(), defaultLimit);
            if (count >= limit * thresholdRatio) {
                sendTypeWarning(chunk, type.name(), count, limit);
            }
        });

        // 处理物品预警
        stats.itemCounts.forEach((material, count) -> {
            if (count >= itemLimit * thresholdRatio) {
                sendTypeWarning(chunk, material.name(), count, itemLimit);
            }
        });
    }

    private void sendTypeWarning(Chunk chunk, String typeName, int current, int limit) {
        String worldName = chunk.getWorld().getName();
        String chunkKey = typeName + ":" + worldName + ":" + chunk.getX() + ":" + chunk.getZ();

        if (!chunk.isLoaded()) {
            lastNotifyTimes.remove(chunkKey);
            return;
        }

        if (System.currentTimeMillis() - lastNotifyTimes.getOrDefault(chunkKey, 0L) > notifyCooldown * 1000L) {
            String message = msgPreOverload
                    .replace("%type%", typeName)
                    .replace("%current%", String.valueOf(current))
                    .replace("%max%", String.valueOf(limit))
                    .replace("%chunkX%", String.valueOf(chunk.getX()))
                    .replace("%chunkZ%", String.valueOf(chunk.getZ()))
                    .replace("%world%", worldName);

            // 发送给区块内的所有玩家
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getWorld().equals(chunk.getWorld()))
                    .filter(p -> p.getLocation().getChunk().equals(chunk))
                    .forEach(p -> p.sendMessage(message));

            // 控制台日志
            if (enableNotifications) {
                getLogger().warning(message);
            }

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

            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        String langPrefix = currentLang.equals("zh") ? "&6用法：" : "&6Usage:";
        String[] helpMessages = currentLang.equals("zh") ?
                new String[] {
                        "&a/chunklimiter reload &7- 重载配置",
                        "&a/chunklimiter notify [on|off] &7- 切换通知状态",
                        "&a/chunklimiter stats &7- 查看当前区块统计"
                } :
                new String[] {
                        "&a/chunklimiter reload &7- Reload config",
                        "&a/chunklimiter notify [on|off] &7- Toggle notifications",
                        "&a/chunklimiter stats &7- Show chunk stats"
                };

        sender.sendMessage(parseMessage(langPrefix));
        for (String msg : helpMessages) {
            sender.sendMessage(parseMessage(msg));
        }
    }

    private void showChunkStats(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        World world = player.getWorld();

        // 生物统计（过滤忽略类型）
        Map<EntityType, Long> mobCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof LivingEntity)
                .filter(e -> !ignoredTypes.contains(e.getType()))
                .collect(Collectors.groupingBy(
                        Entity::getType,
                        Collectors.counting()
                ));

        // 物品统计（过滤忽略类型）
        Map<Material, Long> itemCounts = Arrays.stream(chunk.getEntities())
                .filter(e -> e instanceof Item)
                .map(e -> ((Item) e).getItemStack().getType())
                .filter(material -> !ignoredItems.contains(material))
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
        if (!mobCounts.isEmpty()) {
            player.sendMessage(replacePlaceholders(msgMobStats, baseParams)); // 生物统计标题

            mobCounts.forEach((type, count) -> {
                Map<String, String> params = new HashMap<>(baseParams);
                params.put("type", type.name()); // 直接使用原始枚举名称
                params.put("count", String.valueOf(count));
                params.put("limit", String.valueOf(customLimits.getOrDefault(type.name(), defaultLimit)));
                player.sendMessage(replacePlaceholders(msgMobStatsLine, params));
            });
        }

        // 物品统计部分
        if (!itemCounts.isEmpty()) {
            player.sendMessage(replacePlaceholders(msgItemStats, baseParams)); // 物品统计标题

            itemCounts.forEach((material, count) -> {
                Map<String, String> params = new HashMap<>(baseParams);
                params.put("type", material.name()); // 直接使用原始枚举名称
                params.put("count", String.valueOf(count));
                params.put("limit", String.valueOf(itemLimit));
                player.sendMessage(replacePlaceholders(msgItemStatsLine, params));
            });
        }

        // 总数统计
        Map<String, String> totalParams = new HashMap<>(baseParams);
        totalParams.put("total_mobs", String.valueOf(mobCounts.values().stream().mapToLong(Long::longValue).sum()));
        totalParams.put("total_items", String.valueOf(itemCounts.values().stream().mapToLong(Long::longValue).sum()));
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
            return Arrays.asList("reload", "stats", "notify", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("notify")) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }
}