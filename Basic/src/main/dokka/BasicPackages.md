# Package work.art1st.javarailways.basic.api.ui.config

面向平台 UI 的类型安全配置项、约束、快照与玩家会话接口。当前属于实验性 API。

# Package work.art1st.javarailways.basic.api.ato.snapshot

ATO 规划输入使用的不可变 Route 与 Perception 快照。集合在构造时防御性复制。

# Package work.art1st.javarailways.basic.api.ato.command

主线程或插件向物理 worker 提交命令的 revisioned queue。payload 必须由调用方保证不可变。

# Package work.art1st.javarailways.basic.api.ato

自动/手动控制共用的控制需求、车辆 Actuator 和坡度感知制动包络。它们不选择 mission、ATO安全参数或信号策略。

# Package work.art1st.javarailways.basic.api.ato.provider

world-local 通用 RailObject、双索引 store 与 lifecycle 契约；不包含已删除的 ATO Provider/Boundary 切换机制。

# Package work.art1st.javarailways.basic.api.safety

由 Basic 物理控制读取的 world-local fail-close interlock。它不保存 ATO 诊断或运营状态。

# Package work.art1st.javarailways.basic.api.operation

平台无关语义列车操作、共用的 DoorSide primitive、稳定 request、可确认 ticket 和主线程 Gateway 边界。

# Package work.art1st.javarailways.basic.api.infrastructure

Applied-plan dependency以persistent rail ID、stable topology-node key和runtime resource ID三类引用阻止相关编辑，
但不占用列车资源。Live claim使用独立的原子resource footprint；edit permit使用同三类影响集合并返回逐字段冲突诊断。

# Package work.art1st.javarailways.basic.api.observation

由同一个 Core world frame 投影得到的不可变铁路观察；物理 epoch、拓扑 revision、frame revision、列车位置、epoch末保守占用、整epoch扫过范围与有序边界穿越始终来自同一次发布。插件可用通用监测区定义获取带边界身份的`ENTER`/`LEFT`物理变化，但信号含义仍由插件拥有。

# Package work.art1st.javarailways.basic.api.route

冻结 route intent、route-metre authority/release boundary、插件附加资源、设备准备参数及 grant result。调用方不提交完整 footprint；Basic 按权威拓扑推导不可删除的 rail、turnout 与 route-bound physical resources，并把经过认证的插件 additions 合并进同一个原子 claim。保护区域与固定闭塞属于具体信号插件，不在本 package 定义。
