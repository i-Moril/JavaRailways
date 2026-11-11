# B：整车原生限速实施记录

2026-11-06。范围仅 F4/A4，承接 A 的 TrainFrameSnapshot 接口；未提交、推送、部署或操作远程服务器。

## 改动

- `SimpleATO/.../control/NativeBodySpeedLimits.kt`：19 行共用读取函数。只用 caller 已捕获帧的 bodyTraversal 去重轨道，读取现有 CURVE/SUBGRADE effective limit。空车身、错误列车、脱轨或缺轨道明确拒绝。不缓存，不从转向架反推覆盖，不合成第二份物理帧。
- `SimpleATO/.../controller/SimpleATO.kt`：updateBuiltInConstraints 使用 scan.perception.train，添加每条当前车身限速的 CurrentSpeedLimitConstraint，现有 controlFrame 转换自然进入 reference 和 protection 两列。既有头部 current-transition、前方入口信号与停车处理不变。
- `SimpleATO/.../control/ManualProtectionFrame.kt`：一次捕获 physicalFrame，head 和 body 都消费该帧；用相同函数替代 occupiedRails + live bogies 补集。既有向前扫描、障碍与观察边界保持。
- `SimpleATO/.../control/NativeBodySpeedLimitsTest.kt`：新增 7 个正式参数化/独立用例，见下。

主会话拥有规范更新，本子批未改规范、Basic monitor、资源仲裁、C02 planner 或 I01 索引。

## 验证

成功命令：

```bash
./gradlew :SimpleATO:test --tests '*NativeBodySpeedLimitsTest' \
  --tests '*ManualProtectionFrameTest' --tests '*SimpleATOTest' \
  --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle
```

结果：BUILD SUCCESSFUL（8s）；37 tests，0 failures/errors/skipped。新增 7 + 既有 manual 2 + SimpleATO 28。
`git diff --check` 通过。Gradle 沙箱首次因本地文件锁通信 wildcard IP 不可用而启动失败，改在获准的沙箱外本地运行；不是业务测试失败。

新增断言：

- AB/BA 的真实两车 x=100..120 布置，一米限速段 x=114..115 位于车身下且没有转向架落其上。自动和手动均有距离零、1.5m/s 的 reference 与 protection 对应目标。
- Core 实际平移并 commit 后，只有车尾不再覆盖该段才移除 body 目标；保留的历史输入帧仍读到原限速，helper 不自行读取 latest frame。
- AB/BA 从限制段前方接近时，原入口信号仍产生正距离的两类目标；无当前 body 限速，未把前方限制错误提前当成零距离目标。
- AB/BA 真实 PhysicsCoordinator epoch、HandleTrainActuator、两车异质 Basic 设备：实际行驶，body 覆盖期间 reference 和真实速度均不超过1.5m/s，尾清后实际速度超过2m/s，无 ATP incident，不靠物理 maximum=1.5 的钳制。
- 空 body 明确失败；手动合成只允许读取一次 publishedFrame，且禁止访问 live headLocation，仍形成正确 body 目标。
- 原 manual 测试继续覆盖仅 SUBGRADE 的限制和 null publishedFrame 拒绝；新测试同时提供 CURVE=1.5、SUBGRADE=3，验证 effective 最小值。

初次新增用例的编译/fixture 问题已修正：DeviceSnapshot 使用 size；PhysicsCoordinator 明确 workerThreads=1、automaticFallback=false；维护摆位先 commit 再 reverse，避免读取原摆位。没有为修 fixture 改生产行为。

## 证据边界

自动双目标存在性使用对既有 protected motionControlFrame 的小型反射观察，未增加生产测试入口；不是唯一验收。实际参考决策和运动另由真实 coordinator/Basic 动力学断言验证。没有运行在线服务器或 Minecraft 客户端；完整相关套件与 Tick14219 原数据回放交给主会话。

实现和聚焦 Gradle 已结束，源码/Gradle 入口释放给主会话；此后不继续并行编辑。
