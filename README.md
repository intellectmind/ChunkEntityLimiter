# ![logo](https://github.com/intellectmind/ChunkEntityLimiter/blob/main/icon_40.png) ChunkEntityLimiter

**Read this in other languages: [English](README.md)，[中文](README_zh.md)。**

----------------------------------------------------------------------------------------------------------

#### Entity and Item Chunk Limit Cleanup Plugin for Folia, Paper, Bukkit, Purpur, Spigot.(1.16-1.21.4)

#### Optimize the server by limiting the number of chunk entities and dropped items through passive cleaning.

#### You can modify the default configuration in the ChunkEntityLimiter folder under the plugins folder.

----------------------------------------------------------------------------------------------------------

#### Provide the following 3 commands:

| Command                     | Description                                                                                       | Permission                                      |
|--------------------------|--------------------------------------------------------------------------------------------|-------------------------------------------|
| ```/chunklimit reload```       | Reload configuration                                                                        | chunklimiter.reload (defaults to op)     |
| ```/chunklimit stats```        | View chunk entity statistics and restrictions                                               | chunklimiter.stats (defaults to all)     |
| ```/chunklimit notify [on\off]``` | Control whether to send cleaning reports and over limit warnings to online administrators    | chunklimiter.notify (defaults to op)     |

----------------------------------------------------------------------------------------------------------

#### config.yml

```
# Entity and item drop limits configuration. It will only clear parts that exceed the limits.
entity-limits:
  # Default maximum number of entities allowed per chunk (all entities except those with special configurations)
  default-limit: 100
  # Maximum number of item entities allowed (item drops), note that this counts the merged stack of items
  item-limit: 300
  # Check interval (in game ticks, 20 ticks = 1 second)
  check-interval-ticks: 600  # This means the check occurs every 30 seconds
  # Ignored entity types (these will not be counted or cleared)
  ignored-types:
    - IRON_GOLEM
    - PLAYER
  # Ignored item types (these will not be counted or cleared)
  ignored-items:
    - DIAMOND
    - NETHERITE_INGOT
    - ENCHANTED_GOLDEN_APPLE
  # Custom entity limits (override the default limits)
  custom-limits:
    ZOMBIE: 200
    CREEPER: 200
    ZOMBIFIED_PIGLIN: 200
# Notification settings
settings:
  # Controls whether cleaning reports and limit warnings are sent to the console and online admins. Default: true
  enable-notifications: true
  # Warning notification percentage (0-100). 0 means no warning, only notify players in the current chunk
  notify-threshold: 90
  # Warning cooldown time, in seconds
  notify-cooldown: 10
  # Language option (en/zh)
  language: en
```

----------------------------------------------------------------------------------------------------------

### bStats
![bStats](https://bstats.org/signatures/bukkit/ChunkEntityLimiter.svg)

### Star History
[![Star History Chart](https://api.star-history.com/svg?repos=intellectmind/ChunkEntityLimiter&type=Date)](https://star-history.com/#intellectmind/ChunkEntityLimiter&Date)
