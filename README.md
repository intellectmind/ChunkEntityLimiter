**用其他语言阅读:[英语](README.md)，[中文](README_zh.md)。**

--------------------------------------------------------------------------------------------------------------

####用于Folia、Paper和其他服务器平台的实体和项目块限制清理插件。(1.16-1.21.4)

####通过限制块实体和掉落物品的数量来优化服务器，以防止玩家错误导致物品溢出。

####您可以在plugins文件夹下的ChunkEntityLimiter文件夹中修改默认配置。

--------------------------------------------------------------------------------------------------------------

####提供以下3个命令:

|命令|描述|许可|
|--------------------------|--------------------------------------------------------------------------------------------|-------------------------------------------|
|```/chunklimit重新加载```|重新加载配置|chunklimiter.reload(默认为op)|
|```/chunklimit统计信息```|查看区块实体统计信息和限制|chunklimiter.stats(默认为all)|
|```/chunklimit通知[开\关]```|控制是否向在线管理员发送清理报告和超限警告|chunklimiter.notify(默认为op)|

--------------------------------------------------------------------------------------------------------------

```
# config.yml
#实体和项目下降限制配置。它只会清除超出限制的部分。
实体限制:
#每个区块允许的默认最大实体数(除了具有特殊配置的实体之外的所有实体)
默认限制:100
#允许的项目实体的最大数量(项目删除)，请注意，这将计算项目的合并堆栈
项目限制:300
#检查间隔(在游戏滴答中，20次滴答= 1秒)
check-interval-ticks: 600 #这意味着每30秒检查一次
#被忽略的实体类型(这些不会被计数或清除)
忽略的类型:
-铁傀儡
-玩家
#忽略的项目类型(这些将不被计数或清除)
忽略的项目:
-钻石
-镍铁矿_铸锭
-魔法金苹果
#自定义实体限制(覆盖默认限制)
自定义限制:
僵尸:200
爬虫:200
僵尸化_猪林:200

#通知设置
设置:
#控制是否向控制台和在线管理员发送清理报告和限制警告。默认值:真
启用通知:真
#警告通知百分比(0-100)。0表示不警告，只通知当前区块中的玩家
通知阈值:90
#警告冷却时间，单位为秒
通知-冷却时间:10
#语言选项(中文/中文)
语言:英语
```

--------------------------------------------------------------------------------------------------------------

###bStats
![bStats](https://bstats.org/signatures/bukkit/ChunkEntityLimiter.svg)

### Star History
[![Star History Chart](https://api.star-history.com/svg?repos=intellectmind/ChunkEntityLimiter&type=Date)](https://star-history.com/#intellectmind/ChunkEntityLimiter&Date)
