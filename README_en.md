* *用其他语言阅读:[英语](README_en.md)，[中文](README.md)。**
用于Folia、Paper和其他服务器平台的实体和项目块限制清理插件。
您可以在plugins文件夹下的ChunkLimiter文件夹中修改默认配置。

提供以下两个命令:
/entitylimiterreloadReload配置(权限:chunklimiter.reload默认为op)
/chunk info查看当前块的项目统计信息(权限:chunklimiter.info默认为all)

仅在1.21.4版本测试，其余版本请自行测试。

# config.yml
#阻止实体和拖放对象限制配置将仅清除多余部分
实体限制:
default-limit: 400 #每种生物都有一个默认上限，如果没有单独指定或排除，每个实体都将遵循默认上限
item-limit: 1000 #落物上限，请注意计算是以合并实体堆数为基础的
检查间隔刻度:100 #清洁间隔刻度
自定义限制:#指定个人生物限制
僵尸化_猪林:200
被忽略的类型:#被忽略的生物类型
-铁傀儡
忽略的项目:#被忽略的坠落物类型
-钻石
-金锭
