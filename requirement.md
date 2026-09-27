## 包
- Core: 物理层核心代码，定义了物理、车辆、轨道等基础设备，为不同铁路系统的实现提供底层API。
- ConfigLib: 持久化/配置相关代码
- Basic: 平台无关的基础铁路机制，包括轨道曲线、通用寻路、列车动力学/手柄执行、安全包络、拓扑与物理资源验证、资源仲裁、设备准备和车尾释放；不拥有具体ATO、时刻表或信号制式。
- SimpleATO: 平台无关的运营与自动驾驶模块，拥有ATO/ATP、运行方向、Destination/Station/Depot/Line/Timetable、运营选路、资源检查调度和最严格限制归约。
- SimpleATOBukkit: SimpleATO的Paper/Folia适配源码模块，由BukkitPlugin直接组合并嵌入JavaRailways主插件JAR；它没有独立plugin descriptor或第二个Bukkit生命周期。
- SimpleSignals: 独立安装的具体信号系统插件，通过JavaRailways公开服务接入；它单独打包并拥有自己的Bukkit生命周期，不嵌入主插件JAR。
- BukkitPlugin: 唯一的JavaRailways Bukkit插件与composition root，负责列车模型/玩家渲染、玩家指令、world lifecycle和持久化，并拉起Core、Basic、SimpleATO及SimpleATOBukkit。
- BukkitPluginDev: 游戏内测试代码

日后Core和Basic可以作为Standalone模式。

## 其他要求
1. 在非Core模块里只使用API，不要使用内部类，除非必须使用。
2. 目前API尚未完善。如有必要可以添加新东西到接口，但不要滥用。
