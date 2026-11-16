# Package work.art1st.javarailways.basic.api.ato.snapshot

ATO 规划输入使用的不可变 Route 与 Perception 快照。集合在构造时防御性复制。

# Package work.art1st.javarailways.basic.api.ato.command

主线程或插件向物理 worker 提交命令的 revisioned queue。payload 必须由调用方保证不可变。

# Package work.art1st.javarailways.basic.api.ato

自动/手动控制共用的控制需求、车辆 Actuator 和坡度感知制动包络。它们不选择 mission、ATO安全参数或信号策略。

`VehicleDeviceState`、`VehicleDeviceEquipment`和`VehicleDeviceDynamics`定义第一方控制模块共用的逐车设备值、
设备参数与纯设备响应计算；这部分不承诺独立第三方插件的长期二进制兼容。状态与能力按同次捕获的实际车厢顺序对应，
设备响应计算不积分速度或位移，设备历史仍由Basic物理执行器维护。

# Package work.art1st.javarailways.basic.api.rail

world-local 通用 RailObject、双索引 store 与 lifecycle 契约；不包含已删除的 ATO Provider/Boundary 切换机制。

# Package work.art1st.javarailways.basic.api.safety

由 Basic 物理控制读取的 world-local fail-close interlock。它不保存 ATO 诊断或运营状态。

# Package work.art1st.javarailways.basic.api.operation

平台无关语义列车操作、共用的 DoorSide primitive、稳定 request、可确认 ticket 和主线程 Gateway 边界。

# Package work.art1st.javarailways.basic.api.infrastructure

Infrastructure dependency以persistent rail ID、stable topology-node key和runtime resource ID三类引用阻止相关编辑，
但不占用列车资源。Live claim使用独立的原子resource footprint；edit permit使用同三类影响集合并返回逐字段冲突诊断。

`PhysicalRouteDependencyCompiler`与`PhysicalRouteDependencyCompilation`为第一方模块提供已选路线的物理编辑依赖查询，
不承诺独立第三方插件的长期二进制兼容。编译结果是依赖声明；调用方通过既有依赖服务安装保护，编译本身不授予资源。

# Package work.art1st.javarailways.basic.api.observation

由同一个 Core world frame 投影得到的不可变铁路观察；物理 epoch、拓扑 revision、frame revision、列车位置、epoch末保守占用、整epoch扫过范围与有序边界穿越始终来自同一次发布。插件可用通用监测区定义获取带边界身份的`ENTER`/`LEFT`物理变化，但信号含义仍由插件拥有。

# Package work.art1st.javarailways.basic.api.route

运营规划使用的不可变候选路线值：稳定有向轨道引用、view-local 米制区间、展开路线和调用方 plan revision。
它们不绑定 live world/topology、不证明连通性或资源所有权；资源准入仍需单独验证。

冻结 route intent、route-metre authority/release boundary、插件附加资源、设备准备参数及 grant result。调用方不提交完整 footprint；Basic 按权威拓扑推导不可删除的 rail、turnout 与 route-bound physical resources，并把经过认证的插件 additions 合并进同一个原子 claim。保护区域与固定闭塞属于具体信号插件，不在本 package 定义。

# Package work.art1st.javarailways.basic.api.route.planning

绑定一个不可变 topology revision 的中立寻路限制：禁止有向轨道区间，以及用完整物理路径替代
局部自由通行。具体信号插件可把私有联锁/区域模型编译成这些值；本 package 不暴露保护区域、
信号或资源占用模型。

`PhysicalRailDistance.between`提供只读的两点物理最短弧长查询：位置为rail UUID与canonical AB米，
忽略运营方向、信号和当前道岔选择，但遵守拓扑合法连接；不可达返回null，非法位置抛出参数异常。
调用不申请资源，结果只对应传入的不可变拓扑，调用方负责世界/拓扑有效性。
