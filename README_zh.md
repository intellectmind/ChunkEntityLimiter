# ![logo](https://github.com/intellectmind/ChunkEntityLimiter/blob/main/icon_40.png) ChunkEntityLimiter

**其他语言版本: [English](README.md)，[中文](README_zh.md)。**

----------------------------------------------------------------------------------------------------------

#### 适用于Folia, Paper, Purpur, Bukkit, Purpur, Spigot的实体、掉落物区块限制清理插件(1.16-1.21.4)

#### 通过被动清理的方式，限制区块实体、掉落物数量从而优化服务器

#### 可在plugins文件夹下的ChunkEntityLimiter文件夹内修改默认配置

----------------------------------------------------------------------------------------------------------

#### 提供以下3个命令：

| 命令                     | 描述                                         | 权限                             |
|--------------------------|--------------------------------------------|----------------------------------|
| ```/chunklimit reload```       | 重新加载配置                               | chunklimiter.reload 默认op       |
| ```/chunklimit stats```        | 查看当前区块的物品统计                     | chunklimiter.stats 默认全部      |
| ```/chunklimit notify [on\off]``` | 控制是否发送清理报告和超限警告给在线管理员 | chunklimiter.notify 默认op       |

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
  check-interval-ticks: 600  # 即每30秒检查一次
  # 忽略的实体类型（不会被计数和清理）
  ignored-types:
    - IRON_GOLEM    # 铁傀儡
    - PLAYER
  # 忽略的物品类型（不会被计数和清理）
  ignored-items:
    - DIAMOND      # 钻石
    - NETHERITE_INGOT # 下界合金锭
    - ENCHANTED_GOLDEN_APPLE # 附魔金苹果
  # 自定义实体限制（覆盖默认限制）
  custom-limits:
    ZOMBIE: 200     # 僵尸最大数量
    CREEPER: 200    # 苦力怕
    ZOMBIFIED_PIGLIN: 200

# 通知设置
settings:
  enable-notifications: true # 控制是否发送清理报告和超限警告到控制台和在线管理员，默认：true
  notify-threshold: 90 # 预警通知百分比（0-100），0表示禁用预警，仅通知当前区块的玩家
  notify-cooldown: 10 # 预警冷却时间，单位秒
  language: zh  # 语言选项（en/zh）
```

----------------------------------------------------------------------------------------------------------

### bStats
![bStats](https://bstats.org/signatures/bukkit/ChunkEntityLimiter.svg)

### Star History
[![Star History Chart](https://api.star-history.com/svg?repos=intellectmind/ChunkEntityLimiter&type=Date)](https://star-history.com/#intellectmind/ChunkEntityLimiter&Date)
