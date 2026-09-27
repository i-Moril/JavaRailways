# Module JavaRailways Basic API

建立在 Core 之上的基础铁路系统候选 API。目前收录配置 UI、自动/手动控制共用的物理 actuation 与安全 primitive、route/perception snapshot、TrainOperationGateway、通用寻路与资源准入、RailObject/WayPoint。ATO mission/constraint/coordinator 以及 Destination、Station、Depot/rolling-stock ledger、Line 与 timetable 由 SimpleATO 拥有；旧 AtoProvider 切换机制已经删除。

> 当前版本为 `1.0-SNAPSHOT`，本模块文档属于实验性 API Reference，不构成二进制兼容承诺。
