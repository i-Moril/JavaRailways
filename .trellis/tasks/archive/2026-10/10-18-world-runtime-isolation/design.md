# 子任务设计

## 技术方案

串行执行单位是World。移除整个world遍历/整场模拟的全局锁，并消除global scheduler等待一个World造成的间接阻塞；复用既有ticket、coalescing和帧间队列。跨World重载、provider注册/卸载按有关World边界单独协调；玩家handoff按玩家保留。建立当前领域部件实际需要的捕获/目标World安装接线，统一SpeedSafetyMonitor、脱轨消费、observation/admission/Dispatcher/操作顺序；不建独立Environment或任意对象clone框架。

完整交界契约见[父设计](../../../10-18-isolated-simulation-runtime/design.md)，本任务不能自行改变用户已确认的模式/事件/失败语义。

## O1/O2：observation构造的World归属

现服务的`current/publish`在共享lock内执行`currentLocked`和真实fromCore转换。本批将转换移到共享目录锁外、复用目标World执行边界；只对必要目录/订阅/缓存交换保留短锁。不同World的转换不共享执行锁，同World保持既有观察顺序。不要以删除同步注解、复制一套observation算法或增加Environment/通用缓存框架代替隔离。

保留不可变frame的epoch/identity事实、每epoch最多一次通知和同epoch owner更新、listener锁外调用及异常隔离。close/removeWorld与构造交错的结果不能写回失效的目标绑定；按现有实际World生命周期处理，不引入永久tombstone或额外公开代际协议。

验收在真实转换路径内阻塞A并证明B正常构造/发布，同时验证same-World缓存/通知及退休交错；原owner/runner测试和旧候选绿门禁不足以证明新项。

## 代码ownership

Core的World owner/physics协作入口；BukkitThreadGuardBridge、WorldManager、TimetableHttpBridge、RailWorldFactory、TrainResourceReloader及相关宿主服务/TrainControlManager，补充DefaultRailwayObservationService及其正式测试；SimpleATO隔离runtime和共同装配；Basic安全/资源内核仅在真实复用需要时修改。

派发前按实际最小变更文件限定ownership；其他子任务和会话可能同时存在，不能恢复或覆盖他人改动。源文件或持久化格式若需扩大范围，先记录真实依赖并更新设计。

## 依赖与回滚

依赖10-18-world-epoch-events；在子任务1完成并验证后实施。
每批独立验证/提交；回滚仅撤本子任务提交，保留原合并基线与无关WIP。
