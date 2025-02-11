* *用其他语言阅读:[英语](README_en.md)，[中文](README.md)。**
Entity and Item Chunk Limit Cleanup Plugin for Folia, Paper, and Other Server Platforms.

You can modify the default configuration in the ChunkLimiter folder under the plugins folder.

Provide the following two commands:
/entitylimiterreloadReload configuration (permission:chunklimiter.reload defaults to op)
/chunkinfoView the item statistics of the current block (permission:chunklimiter.info defaults to all)

Only tested in version 1.21.4, please test the remaining versions yourself.

# config.yml
# Block entity and drop object restriction configuration will only clear the excess parts
entity-limits:
  default-limit: 400      # Each type of organism has a default upper limit, and if not specified or excluded separately, each entity will follow the default limit
  item-limit: 1000        # The upper limit of falling objects, please note that the calculation is based on the number of merged entity piles
  check-interval-ticks: 100        # Cleaning interval tick
  custom-limits:         # Specify individual biological limits
    ZOMBIFIED_PIGLIN: 200
  ignored-types:         # Neglected biological types
    - IRON_GOLEM
  ignored-items:         # Neglected types of falling objects
    - DIAMOND
    - GOLD_INGOT
