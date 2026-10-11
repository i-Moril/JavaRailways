# Module JavaRailways Core API

平台无关的轨道、列车、曲线、事件、Tick 和扩展数据候选 API。

> 当前版本为 `1.0-SNAPSHOT`，本模块文档属于实验性 API Reference，不构成二进制兼容承诺。

调用前请特别检查声明上的线程注解和 KDoc。未标记为主线程 API 的声明也不代表可以安全地并发调用。

联结/解编及对应事件单独以 `TrainStructureApi` 标记为 unstable：属于正式能力，但签名与语义仍可能调整，
不保证后续源码或二进制兼容。Kotlin 使用方可通过 `@OptIn(TrainStructureApi::class)` 明确接受该约定；
Java 使用方同样需要检查对应 KDoc。
