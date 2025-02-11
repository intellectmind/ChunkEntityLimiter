**其他语言版本: [English](README.md), [中文](README_zh.md).**

适用于Folia、Paper、Purpur、luminol等服务端的实体、掉落物区块限制清理插件

可在plugins文件夹下的ChunkLimiter文件夹内修改默认配置

提供以下2个命令：

```/entitylimiterreload```重新加载配置（权限chunklimiter.reload默认op）

```/chunkinfo```查看当前区块的物品统计（权限chunklimiter.info默认全部）

![2](https://github.com/user-attachments/assets/302e93a9-2452-4890-814d-e2afe609961f)

只在1.21.4版本进行了测试,其余版本请自行测试

```
# config.yml
# 区块实体、掉落物限制配置，只会清理超出的部分
entity-limits:
  default-limit: 400      # 生物每类默认上限,未单独指定或排除则每种实体都按默认的来
  item-limit: 1000        # 掉落物上限,注意计算的是合并后的实体堆数量
  check-interval-ticks: 100        # 清理间隔tick，一般20为1秒
  custom-limits:         # 单独指定生物上限
    ZOMBIFIED_PIGLIN: 200         # 僵尸猪灵200
  ignored-types:         # 忽略的生物类型
    - IRON_GOLEM         # 忽略铁傀儡
  ignored-items:         # 忽略的掉落物类型
    - DIAMOND            # 钻石不会被清理
    - GOLD_INGOT         # 金锭不会被清理
```
