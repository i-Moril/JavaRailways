# Core 新建列车的唯一物理放置

适用：修改 `TrainCreation.prepareUniquePlacement`、prepared placement 的消费/清理或模拟播种。普通运行几何与局部数值支持边界仍归[train physics](./train-physics.md)。


模拟播种通过 `TrainCreation.prepareUniquePlacement(cars, head)` 在帧间 owner 中准备，返回 `Unique`、`Unplaceable`、`Ambiguous` 或 `SearchLimit`。起点及方向由调用层明确提供；Core 只处理有向物理拓扑和车体几何，不把运行方向许可当作静止停车规则。准备阶段不注册候选列车、不改变车辆或道岔，不调用列车扩展构造器。

沿车头向后枚举全部 `nextRails`，复用 CoreCar 的真实三维轴距、车钩求解及既有有限搜索窗口。路径尚未展开与尽头不可放置必须区分；不能用当前 activeRail 或名义车长之和证明唯一。完成后按实际车尾裁剪并去重：数值支持窗口看到但车体不占用的分支不增加放置数量。重复轨道 occurrence 保序，不相交的首尾片段可以组成环线上的同一车体；真正自重叠拒绝。

只有穷尽支持范围内的相关路径且恰有一个完整候选才能返回 Unique；已找到候选但预算耗尽、中断或无法证明时返回 SearchLimit。当前每次准备最多4096次路径展开，无逐tick搜索。Opaque prepared placement 绑定 exact world/topology/cars/安装方向，只能消费一次；`train(preparedPlacement=...)` 安装已求解几何和必要道岔选择，不能再按 active path 重算。构造后及 publish 前核对证明未被初始化回调改写；失败沿创建作用域清理并恢复已改道岔。

回归覆盖双可行分支、短分支、车尾外支持窗口、曲线/坡道/混装、AB/BA接缝、环线、自重叠、预算不足和过期证明。普通 `setLocation` 的既有路径选择不改。
