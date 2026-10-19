# 子任务设计

## 技术方案

提取既有逐车行为的逻辑与效果接口并由实际/模拟共同调用；每车/每模拟新建JS状态。保留方向、拒绝、延迟效果汇总、取消和失败，模拟只记录效果并以逻辑时钟推进。删除只按固定配置时长返回成功的执行替身。领域扩展按目标World创建实例；平台Feature排除，其他必需Feature不得被旧opt-in筛选悄悄丢弃。不添加通用JS沙箱或Bukkit模拟器。

完整交界契约见[父设计](../10-18-isolated-simulation-runtime/design.md)，本任务不能自行改变用户已确认的模式/事件/失败语义。

## 代码ownership

Bukkit现有CarBehavior/TrainBehaviorRuntime、逐车renderer调用及operation/profile capture；Basic TrainOperation契约；SimpleATO simulation gateway、扩展registry和共同安装consumer。

派发前按实际最小变更文件限定ownership；其他子任务和会话可能同时存在，不能恢复或覆盖他人改动。源文件或持久化格式若需扩大范围，先记录真实依赖并更新设计。

## 依赖与回滚

依赖10-18-world-runtime-isolation；本子任务最终联合验证也依赖10-18-simulation-signal-features。
每批独立验证/提交；回滚仅撤本子任务提交，保留原合并基线与无关WIP。
