# ![logo](https://github.com/intellectmind/ChunkEntityLimiter/blob/main/icon_40.png) ChunkEntityLimiter

**Read this in other languages: [English](README.md)，[中文](README_zh.md)。**

----------------------------------------------------------------------------------------------------------

#### Entity and Item Chunk Limit Cleanup Plugin for Folia, Paper, Bukkit, Purpur, Spigot.

#### This optimization method enhances Minecraft server performance by intelligently limiting entity and item drop counts in both individual chunks and player-surrounding chunks.

#### You can modify the default configuration in the ChunkEntityLimiter folder under the plugins folder.

----------------------------------------------------------------------------------------------------------

#### Provide the following 4 commands:

| Command                     | Description                                                                                       | Permission                                      |
|--------------------------|--------------------------------------------------------------------------------------------|-------------------------------------------|
| ```/chunklimit reload```       | Reload configuration                                                                        | chunklimiter.reload (defaults to op)     |
| ```/chunklimit stats```        | View chunk entity statistics and restrictions                                               | chunklimiter.stats (defaults to all)     |
| ```/chunklimit notify [on\off]``` | Control whether to send cleaning reports and over limit warnings to online administrators    | chunklimiter.notify (defaults to op)     |
| ```/chunklimit performance [reset]``` | View\Reset Performance Monitoring (Due to the caching mechanism, it takes about 10 rounds to run accurately) | chunklimiter.performance (defaults to op)       |

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
  check-interval-ticks: 200  # This means the check occurs every 10 seconds
  # Whether to check chunks around the player. 0 means disabled.
  # Example: If chunk-check-radius: 1 and default-limit: 100, the total limit for all chunks (3x3) within radius 1 around the player is also 100. Single-chunk limits still apply.
  chunk-check-radius: 1
  # Multiplier for entity limits in chunks around the player.
  # Example: If chunk_entity_multiplier: 1.5, chunk-check-radius: 1, and default-limit: 100, the entity limit for all chunks (3x3) within radius 1 becomes 100 * 1.5.
  chunk_entity_multiplier: 1.5
  # Multiplier for item entity limits in chunks around the player.
  # Example: If chunk_item_multiplier: 1.5, chunk-check-radius: 1, and default-limit: 100, the item limit for all chunks (3x3) within radius 1 becomes 100 * 1.5.
  chunk_item_multiplier: 1.5
  # Ignored entity types (these will not be counted or cleared)
  ignored-types:
    - IRON_GOLEM
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

# Protection Settings, entities exceeding the limit will not be prioritized for cleaning, and protected entities exceeding the limit will still be cleaned up
protection:
  # Protect entities with custom names
  protect-named-entities: true
  # Protect leashed mobs (horses/donkeys excluded by default)
  protect-leashed-entities: true
  # Prevent harming tamed pets (dogs/cats/parrots)
  protect-tamed-animals: true
  # Protect mobs wearing armor/items (including dropped gear)
  protect-equipped-entities: true
  # Prevent altering boss-type mobs (Ender Dragon, Wither, etc.)
  protect-boss-entities: true

# Notification settings
settings:
  # Controls whether cleaning reports and limit warnings are sent to the console and online player. Default: true
  enable-notifications: true
  # Warning notification percentage (0-100). 0 means no warning, only notify players in the current chunk
  notify-threshold: 90
  # Warning cooldown time, in seconds
  notify-cooldown: 10
  # Player notification scope
  notification-radius: 128.0
  # Set to true to enable performance monitoring
  performance-monitoring: false
  # Language option (en/zh)
  language: en
```

----------------------------------------------------------------------------------------------------------

### bStats
![bStats](https://bstats.org/signatures/bukkit/ChunkEntityLimiter.svg)

### Star History
[![Star History Chart](https://api.star-history.com/svg?repos=intellectmind/ChunkEntityLimiter&type=Date)](https://star-history.com/#intellectmind/ChunkEntityLimiter&Date)
