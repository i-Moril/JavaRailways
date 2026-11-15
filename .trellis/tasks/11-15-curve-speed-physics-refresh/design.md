# 设计

## Owner 与流程
Basic RailSpeedCalculator / RailSpeedPhysics 拥有公式与倍率；Bukkit PhysicsConfig 读取 track 参数，默认资源与配置测试同步。
Basic 复用轨道元数据更新与 OperationalSpeedLimitSignalMaterializer 边界；Bukkit RailWorldFactory 在 basicInfrastructure.restore 后、materialize 与世界发布前按当前物理配置刷新派生值。
RailWand 建造与编辑的所有计算入口统一传递 railSpeedPhysics；保留人工请求的现有检查与设计速度限制。检查其他几何编辑入口，确保没有旧派生值残留。
隔离模拟从更新后的来源快照构造，核实其路径没有绕过刷新。

## 数据合同
保留 authoring 中 automatic 标志、欠超高、SUBGRADE 及人工 CURVE 值。自动 CURVE 和 curveSafety 是当前几何与物理配置的计算结果。保存形状不变，加载时重新推导自动结果。
新增配置字段的默认值拟为 1.0，版本号按共享发布基线政策处理；不添加兼容分支或离线迁移。

## 权衡
计算成本发生在加载和轨道编辑阶段；运行阶段继续直接查询限速。提高运营倍率不改变独立脱轨物理参数，最终限制保留脱轨上限和线路设计上限。

## 风险
当前 main 的远端最新状态需核实：SSH fetch 公钥认证失败，尝试 GitHub 只读 API。自动结果与手工值的区分必须使用已有明确 authoring 标志。启动初始化顺序必须先刷新再发布信号和 ATO 输入。
