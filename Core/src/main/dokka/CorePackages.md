# Package work.art1st.javarailways.core.api.physics

平台无关的铁路世界入口。对象状态可能由异步物理 epoch 发布，扩展不得假设所有 getter 都在 Bukkit 主线程更新。

# Package work.art1st.javarailways.core.api.physics.rail

轨道、方向视图、位置、运营对象稳定引用与不可变快照。弧长单位为米，并以 Rail 的 AB 曲线作为持久化基准。

# Package work.art1st.javarailways.core.api.physics.rail.topology

按 runtime revision 原子发布的不可变物理连通图。Node/edge ID 只在所属 revision 内有效，不是持久化身份。

# Package work.art1st.javarailways.core.api.physics.train

列车、车厢、转向架与渲染扩展接口。结构修改必须遵守宿主提供的线程边界。

`TrainEndRailObject`提供车端的live owner与运行方向HEAD/TAIL身份。它复用原RailObject引用与生命周期，
不表示不可变车端快照，也不把HEAD/TAIL当作永久物理端A/B。


# Package work.art1st.javarailways.core.api.control

列车控制器和控制命令。控制器由物理 Coordinator 调度，不是 Bukkit 主线程回调。

# Package work.art1st.javarailways.core.api.event

Core 事件 payload。事件对象不等同于 Bukkit Event，派发线程取决于调用入口。

# Package work.art1st.javarailways.core.api.event.processor

按主线程和 physics worker 分离的同步事件总线。注册返回的 token 必须与扩展插件生命周期配对关闭。

# Package work.art1st.javarailways.core.api.threading

线程身份和 fail-fast 契约。`ThreadGuard` 只判断当前线程，不负责调度或授予 owner 权限；普通 consumer
不能安装或清除平台 bridge。

# Package work.art1st.javarailways.core.api.threading.host

可信平台宿主的启动/停用集成面。第一个 bridge registration 获得 identity-bound token；重复注册被拒绝，
关闭 token 幂等且只能移除自己的注册。该包不是普通附属插件的调度入口。

# Package work.art1st.javarailways.core.api.util.plugin

为 World、Train、Car 等宿主挂载扩展数据的低层机制。当前 registry 在启动阶段固定槽位，尚不是第三方插件的稳定服务入口。

# Package work.art1st.javarailways.core.api.util.tick

通用 Tick 对象、提交边界与性能指标。列车物理使用独立 PhysicsCoordinator。

# Package work.art1st.javarailways.core.api.util.annotation

线程、开销和纯函数约定。注解只表达契约，不会自动切换线程。
