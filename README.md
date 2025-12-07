# ![logo](https://github.com/intellectmind/ChunkEntityLimiter/blob/main/icon_40.png) ChunkEntityLimiter

**Read this in other languages: [English](README.md)，[中文](README_zh.md)。**

----------------------------------------------------------------------------------------------------------

**High-Performance Entity & Item Limiter for Folia, Paper, Bukkit, Purpur, and Spigot.**

This plugin optimizes server performance by intelligently limiting entity and item counts. It employs a **dual-check system** (Single Chunk Limit + Player Surrounding Chunks Limit) to prevent lag from mob farms or massive item drops, while ensuring important entities are protected.

## Key Features

*   **Native Folia Support:** Fully optimized for Folia's regional threading architecture.
*   **Smart Cleanup Logic:** **Oldest First.** Entities are removed based on their generation time. The longest-living entities are removed first to preserve newly spawned mobs.
*   **Dual-Layer Limiting:** Limits are applied to individual chunks *and* the aggregate group of chunks around a player.
*   **Precise Item Counting:** Configurable option to count item stacks by their actual amount (e.g., a stack of 64 Cobblestone counts as 64, not 1).
*   **Granular Notifications:** Separate controls for **Cleanup Reports** and **Overload Warnings**, with configurable scopes (None, OP only, or All Players nearby).
*   **Performance Monitoring:** Built-in performance profiler to track cleanup phases.

------------------------------------------------------------------------------------------------------

## Cleanup Logic

1.  **Classification:** Entities are categorized into "Protected" and "Removable".
2.  **Protection Check:** Protected entities (Named, Leashed, Tamed, Equipped, Bosses) are skipped unless `clean-protected-if-over-limit` is enabled.
3.  **Sorting:** Removable entities are sorted by **Spawn Time**.
4.  **Execution:** The plugin removes entities starting from the **Earliest Generated (Oldest)** until the count drops below the limit.

------------------------------------------------------------------------------------------------------

## Commands & Permissions

**Main Command:** `/chunklimit`
**Aliases:** `/cl`, `/climit`

| Command | Description | Permission |
| :--- | :--- | :--- |
| **`/chunklimit reload`** | Reloads the configuration file. | `chunklimiter.reload` (default: op) |
| **`/chunklimit stats`** | Displays detailed entity statistics for the current chunk and surrounding radius. | `chunklimiter.stats` (default: true) |
| **`/chunklimit notify [type] [scope]`** | **New in v1.9+**<br>Set notification preferences.<br>• **Type:** `report` (Cleanup result) or `warning` (Near limit)<br>• **Scope:** `none` (Off), `op` (Admins only), `all` (Everyone nearby)<br>_Example:_ `/cl notify report op` | `chunklimiter.notify` (default: op) |
| **`/chunklimit performance [reset]`** | View performance metrics (execution time for classification, cleanup, etc.). | `chunklimiter.performance` (default: op) |

------------------------------------------------------------------------------------------------------

#### config.yml

```
# Entity and item drop limits configuration. It will only clear parts that exceed the limits.
# 区块实体、掉落物限制配置，只会清理超出的部分
entity-limits:
  # Default maximum number of entities allowed per chunk (all entities except those with special configurations)
  # 默认每个区块允许的最大实体数量（除特殊配置外的所有实体）
  default-limit: 100

  # Maximum number of item entities allowed (item drops)
  # 物品实体的最大数量限制（掉落物）
  item-limit: 300

  # Check interval (in game ticks, 20 ticks = 1 second)
  # Recommended: 600 (30s) or 1200 (60s) for performance
  # 检查间隔（单位：游戏刻，20 ticks = 1秒）
  # 推荐设置：600 (30秒) 或 1200 (60秒) 以节省性能
  check-interval-ticks: 600

  # Whether to check chunks around the player. 0 means disabled.
  # Example: If chunk-check-radius: 1, the total limit for all chunks (3x3) within radius 1 around the player is calculated.
  # 是否包含检查玩家周围的区块，0表示禁用
  # 例如: 设置为 1 表示检查玩家周围 3x3 的区块范围
  chunk-check-radius: 2

  # Multiplier for entity limits in chunks around the player.
  # Example: If set to 1.5, the limit for chunks in the radius will be 1.5x the default limit.
  # 玩家周围的区块实体限制倍数 (宽松系数)
  # 例如: 设置为 1.5，则周围区块的实体上限是默认值的 1.5 倍
  chunk_entity_multiplier: 1.5

  # Multiplier for item entity limits in chunks around the player.
  # 玩家周围的区块物品实体限制倍数
  chunk_item_multiplier: 1.5

  # Whether to count item stack amounts towards the limit
  # If true, a stack of 64 items counts as 64. If false, it counts as 1 entity.
  # 是否计算物品堆叠数量
  # 若为 true，一组64个物品算作64。若为 false，算作1个实体。
  count-item-stack-amount: false

  # Ignored entity types (these will not be counted or cleared)
  # 忽略的实体类型（不会被计数和清理）
  ignored-types:
    - IRON_GOLEM    # Iron Golem / 铁傀儡
    - VILLAGER      # Villager / 村民

  # Ignored item types (these will not be counted or cleared)
  # 忽略的物品类型（不会被计数和清理）
  ignored-items:
    - DIAMOND                # Diamond / 钻石
    - NETHERITE_INGOT        # Netherite Ingot / 下界合金锭
    - ENCHANTED_GOLDEN_APPLE # Enchanted Golden Apple / 附魔金苹果

  # Custom entity limits (override the default limits)
  # 自定义实体限制（覆盖默认限制）
  custom-limits:
    ZOMBIE: 200     # Maximum number of Zombies / 僵尸最大数量
    CREEPER: 200    # Maximum number of Creepers / 苦力怕最大数量
    ZOMBIFIED_PIGLIN: 200  # Maximum number of Zombified Piglins / 猪人最大数量
    
# Protection Settings, entities exceeding the limit will not be prioritized for cleaning, and protected entities exceeding the limit will still be cleaned up
# 保护设置，实体超过限制时不会被优先清理，受保护的实体数量超过限制时仍将被清理
protection:
  # Protect entities with custom names
  # 保护被命名的实体
  protect-named-entities: true

  # Protect leashed mobs (horses/donkeys excluded by default)
  # 保护被拴住的实体
  protect-leashed-entities: true

  # Prevent harming tamed pets (dogs/cats/parrots)
  # 保护驯服的动物
  protect-tamed-animals: true

  # Protect mobs wearing armor/items (including dropped gear)
  # 保护有装备的实体
  protect-equipped-entities: true

  # Prevent altering boss-type mobs (Ender Dragon, Wither, etc.)
  # 保护Boss实体
  protect-boss-entities: true

# Notification settings
# 通知设置
settings:
  # Whether to clean up protected entities when the limit is exceeded
  # If true: Normal entities are removed first, then protected ones if still over limit.
  # If false: Protected entities are never removed (but count towards the total).
  # 当数量超限时，是否强制清理受保护的实体
  # 若为 true：先清理普通实体，若仍超限则清理保护实体。
  # 若为 false：受保护实体永远不会被清理（但会占用数量名额）。
  clean-protected-if-over-limit: true

  # Whether to enable global single chunk cleaning (Phase 1).
  # If false: Skips global scan, only cleans chunks near players (Phase 2). Recommended false for Folia.
  # 是否启用全局单区块清理（阶段1）。
  # 若为 false：跳过全局扫描，仅清理玩家附近的区块（阶段2）。Folia 端建议设为 false 以提升性能。
  clean-all-loaded-chunks: true

  # Notification scope for cleanup reports (when entities are removed)
  # Options: NONE (disable), OP (admins only), ALL (all players in radius)
  # 清理报告通知范围（当实体被清理时）
  # 选项: NONE (禁用), OP (仅管理员), ALL (所有玩家)
  cleanup-report-scope: ALL

  # Notification scope for overload warnings (when nearing limit)
  # Options: NONE (disable), OP (admins only), ALL (all players in radius)
  # 超限预警通知范围（当接近上限时）
  # 选项: NONE (禁用), OP (仅管理员), ALL (所有玩家)
  overload-warning-scope: ALL

  # Whether OPs should receive global notifications for cleanup reports
  # If true: OPs receive reports from all chunks. If false: OPs must be nearby.
  # 是否向OP发送全局清理报告通知（全服广播给OP）
  # 若为 true：OP 接收所有区块的报告。若为 false：OP 必须在附近。
  op-global-cleanup-report: true

  # Whether OPs should receive global notifications for overload warnings
  # If true: OPs receive warnings from all chunks. If false: OPs must be nearby.
  # 是否向OP发送全局超限预警通知（全服广播给OP）
  # 若为 true：OP 接收所有区块的预警。若为 false：OP 必须在附近。
  op-global-overload-warning: false

  # Whether to send cleanup reports to the server console
  # 是否将清理报告发送到服务器控制台
  console-cleanup-report: true

  # Warning notification percentage (0-100).
  # 预警通知百分比（0-100）
  notify-threshold: 90

  # Warning cooldown time, in seconds
  # 预警冷却时间，单位秒
  notify-cooldown: 10

  # Player notification scope radius
  # 玩家通知范围半径
  notification-radius: 128.0

  # Set to true to enable performance monitoring
  # 设为 true 启用性能监控
  performance-monitoring: false

  # Set to true to enable debug mode (verbose logging)
  # 设为 true 启用调试模式（详细日志）
  debug-mode: false

  # Language option (en/zh)
  # 语言选项（en/zh）
  language: en
```

----------------------------------------------------------------------------------------------------------

### bStats
![bStats](https://bstats.org/signatures/bukkit/ChunkEntityLimiter.svg)

### Star History
[![Star History Chart](https://api.star-history.com/svg?repos=intellectmind/ChunkEntityLimiter&type=Date)](https://star-history.com/#intellectmind/ChunkEntityLimiter&Date)
