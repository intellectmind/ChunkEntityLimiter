# ![logo](https://github.com/intellectmind/ChunkEntityLimiter/blob/main/icon_40.png) ChunkEntityLimiter

**其他语言版本: [English](README.md)，[中文](README_zh.md)。**

----------------------------------------------------------------------------------------------------------

#### 适用于Folia, Paper, Purpur, Bukkit, Purpur, Spigot的实体、掉落物区块限制清理插件

#### 通过被动清理的方式，限制单区块以及玩家周围的区块实体、掉落物数量从而优化服务器

#### 可在plugins文件夹下的ChunkEntityLimiter文件夹内修改默认配置

----------------------------------------------------------------------------------------------------------

#### 提供以下4个命令：

| 命令                     | 描述                                         | 权限                             |
|--------------------------|--------------------------------------------|----------------------------------|
| ```/chunklimit reload```       | 重新加载配置                               | chunklimiter.reload 默认op       |
| ```/chunklimit stats```        | 查看当前区块的物品统计                     | chunklimiter.stats 默认全部      |
| ```/chunklimit notify [on\off]``` | 控制是否发送清理报告和超限警告给在线玩家 | chunklimiter.notify 默认op       |
| ```/chunklimit performance [reset]``` | 查看\重置性能监控（由于有缓存机制，需要运行10轮左右才准确） | chunklimiter.performance 默认op       |

----------------------------------------------------------------------------------------------------------

#### 配置文件（config.yml）

```
# 区块实体、掉落物限制配置，只会清理超出的部分
entity-limits:
  # 默认每个区块允许的最大实体数量（除特殊配置外的所有实体）
  default-limit: 100
  # 物品实体的最大数量限制（掉落物）,注意计算的是合并后的实体堆数量
  item-limit: 300
  # 检查间隔（单位：游戏刻，20 ticks = 1秒）
  check-interval-ticks: 200  # 即每10秒检查一次
  # 是否包含检查玩家周围的区块，0表示禁用
  # 例如: 当chunk-check-radius: 1, default-limit: 100时, 则玩家周围半径1内的所有区块(3x3)的总限制也为100, 单区块限制也同时生效
  chunk-check-radius: 1
  # 玩家周围的区块实体限制倍数
  # 例如: 当chunk_entity_multiplier: 1.5, chunk-check-radius: 1, default-limit: 100时, 则玩家周围半径1内的所有区块(3x3)的实体限制为100*1.5
  chunk_entity_multiplier: 1.5
  # 玩家周围的区块物品实体限制倍数
  # 例如: 当chunk_entity_multiplier: 1.5, chunk-check-radius: 1, default-limit: 100时, 则玩家周围半径1内的所有区块(3x3)的物品限制为100*1.5
  chunk_item_multiplier: 1.5
  # 忽略的实体类型（不会被计数和清理）
  ignored-types:
    - IRON_GOLEM    # 铁傀儡
  # 忽略的物品类型（不会被计数和清理）
  ignored-items:
    - DIAMOND      # 钻石
    - NETHERITE_INGOT # 下界合金锭
    - ENCHANTED_GOLDEN_APPLE # 附魔金苹果
  # 自定义实体限制（覆盖默认限制）
  custom-limits:
    ZOMBIE: 200     # 僵尸最大数量
    CREEPER: 200    # 苦力怕
    ZOMBIFIED_PIGLIN: 200 # 猪人

# 保护设置，实体超过限制时不会被优先清理，受保护的实体数量超过限制时仍将被清理
protection:
  # 保护被命名的实体
  protect-named-entities: true
  # 保护被拴住的实体
  protect-leashed-entities: true
  # 保护驯服的动物
  protect-tamed-animals: true
  # 保护有装备的实体
  protect-equipped-entities: true
  # 保护Boss实体
  protect-boss-entities: true

# 通知设置
settings:
  enable-notifications: true # 控制是否发送清理报告和超限警告到控制台和在线玩家，默认：true
  notify-threshold: 90 # 预警通知百分比（0-100），0表示禁用预警，仅通知当前区块的玩家
  notify-cooldown: 10 # 预警冷却时间，单位秒
  notification-radius: 128.0 # 玩家通知范围
  performance-monitoring: false # 设为 true 启用性能监控
  language: zh  # 语言选项（en/zh）
```

----------------------------------------------------------------------------------------------------------

### bStats
![bStats](https://bstats.org/signatures/bukkit/ChunkEntityLimiter.svg)

### Star History
[![Star History Chart](https://api.star-history.com/svg?repos=intellectmind/ChunkEntityLimiter&type=Date)](https://star-history.com/#intellectmind/ChunkEntityLimiter&Date)
