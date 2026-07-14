# Package work.art1st.mineRails.core.api.physics

平台无关的铁路世界入口。对象状态可能由异步物理 epoch 发布，扩展不得假设所有 getter 都在 Bukkit 主线程更新。

# Package work.art1st.mineRails.core.api.physics.rail

轨道、方向视图、位置与 metadata。弧长单位为米，并以 Rail 的 AB 曲线作为持久化基准。

# Package work.art1st.mineRails.core.api.physics.train

列车、车厢、转向架与渲染扩展接口。结构修改必须遵守宿主提供的线程边界。

# Package work.art1st.mineRails.core.api.control

列车控制器和控制命令。控制器由物理 Coordinator 调度，不是 Bukkit 主线程回调。

# Package work.art1st.mineRails.core.api.event

Core 事件 payload。事件对象不等同于 Bukkit Event，派发线程取决于调用入口。

# Package work.art1st.mineRails.core.api.event.processor

全局轻量事件总线。注册和注销必须与扩展插件生命周期配对。

# Package work.art1st.mineRails.core.api.util.plugin

为 World、Train、Car 等宿主挂载扩展数据的低层机制。当前 registry 在启动阶段固定槽位，尚不是第三方插件的稳定服务入口。

# Package work.art1st.mineRails.core.api.util.tick

通用 Tick 对象、提交边界与性能指标。列车物理使用独立 PhysicsCoordinator。

# Package work.art1st.mineRails.core.api.util.annotation

线程、开销和纯函数约定。注解只表达契约，不会自动切换线程。
