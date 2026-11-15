# 实施顺序

1. 最终方案获用户确认后启动任务，核实 main 与远端状态。
2. 加载 Basic safety/rail/persistence、Bukkit lifecycle/config、共享持久化规范，配置 implement/check manifests。
3. Trellis implement 子代理实现倍率、加载刷新、编辑入口及真实回归测试，同步配置样例和文档。
4. Trellis check 子代理检查公式、人工值、直线、重复加载、信号与隔离模拟一致性，修复发现的问题。
5. 运行 Basic 公式与元数据测试、Bukkit PhysicsConfig/加载与工具相关测试及所需编译；执行 git diff --check。
6. 更新最小 owner specs，在 main 精确暂存并提交本次文件，归档任务并报告提交与验证边界。

## 验证命令
./gradlew :Basic:test --tests '*RailSpeedCalculatorTest' --tests '*OperationalSpeedLimitSignalTest'
./gradlew :BukkitPlugin:test --tests '*PhysicsConfigTest' --tests '*RailWand*' --tests '*RailWorldFactory*'
根据新增刷新测试的真实类名补充测试过滤器，并验证相关下游编译。
