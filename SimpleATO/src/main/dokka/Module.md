# Module JavaRailways SimpleATO API

建立在 Core 与 Basic 之上的平台无关自动行车模块。当前内部runtime已经拥有具体SimpleATO controller与Dispatcher；公开范围包含外部信号系统的冻结路线绑定、预编译资源检查声明及其按需同步更新接口、向单一列车保护管线发布限制所需的不可变契约，以及在完整railway physics epoch内同步消费自己监测区变化的Java/Kotlin callback。

> 当前版本为 `1.0-SNAPSHOT`，本模块文档属于实验性 API Reference，不构成二进制兼容承诺。

具体信号制式、Bukkit 服务注册、presentation adapter 与脚本运行时不属于本模块当前公开 API。
