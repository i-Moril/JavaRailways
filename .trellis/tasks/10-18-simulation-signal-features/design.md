# 子任务设计

## 技术方案

捕获current定义、规则源/逻辑元数据、有效外观名及静态方向/速度设置，对模拟topology重新编译并新建SignalLogicRuntime。完整安装occupancy/epoch source、route planning、两类inspection、固定闭塞资源policy及admission更新。提取必要中立值/效果出口，复用全部既有编译与规则，不共享实际publication绑定/JS实例/模型回调。沿用独立插件身份和存储；不顺带实施全部SimpleSignalsBukkit拆分。

完整交界契约见[父设计](../10-18-isolated-simulation-runtime/design.md)，本任务不能自行改变用户已确认的模式/事件/失败语义。

## 代码ownership

SimpleSignals现有runtime、block/signal策略、signalpack逻辑目录及必要中立元数据；Bukkit公开host安装接线；SimpleATO simulation/inspection/signal runtime的真实安装consumer。

派发前按实际最小变更文件限定ownership；其他子任务和会话可能同时存在，不能恢复或覆盖他人改动。源文件或持久化格式若需扩大范围，先记录真实依赖并更新设计。

## 依赖与回滚

依赖10-18-world-runtime-isolation；使用其已验证的捕获/安装和本地资源接线。
每批独立验证/提交；回滚仅撤本子任务提交，保留原合并基线与无关WIP。
