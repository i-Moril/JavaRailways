# F6–F8 独立复核

2026-11-06；比较基线 `b89b0d5d`，审查共享工作区的本批补充修复；既有 F1–F5 和无关未提交修改保留。本文不是全模块重审，也不是服务器验收。

## 已修复

- `entry-clearance.md` 性能段仍写成“仅存在净空要求才读取帧和定位进度”，与新控制器不符。已改为复用本次帧及外部授权定位，仅车身跨度计算按净空声明条件执行；无生产行为变更。

## 已核对

- `SimpleATO.plan` 一次捕获 Core 帧，保留授权按真实物理位置定位一次，inspection、adapter、净空共用；相同 Mission 的运营进度从其换算，交接时新 Mission 单独定位。旧的 Mission 不匹配即返回 route.totalLength 分支已删除。
- occurrence 提示仅保存距离、帧 revision 与绑定朝向；未变化 route 对象快速复用、相同物理前缀刷新/追加保留提示，新轴重建提示。实际位置仍取当前帧，不能由提示替代。
- `FrozenMissionRoute.physicalHeadProgressMeters` 按实际有向区间定位，已有起止值直接复用，内部偏移先局部相减；找不到位置明确失败。部分授权真实越界保留负距离，不截断、不新增容差。
- `DirectedRouteOccurrence.routeDistanceAt` 的 AB/BA 端点复用及局部偏移由固定闭塞和区域 matcher 共用。固定闭塞终点取已转换的真实交集终点与请求边界的最小值，未重新“起点加长度”；block-local 范围仍独立保留。
- 定向测试覆盖完整/部分旧授权下新 Mission 激活、AB/BA、真实 PhysicsCoordinator epoch、旧 Mission 退休和折返后重新绑定、真实越界；纯投影测试覆盖重复经过、小数端点和相邻浮点值。信号测试覆盖两个坐标方向、内部交点、部分裁剪及真实重复经过。

## 整合检查发现（实现方已修复并通过聚焦验证）

主会话完整 SimpleATO 测试暴露四项失败：三项折返后新 Mission 已激活而 controller 仍持旧轴 binding；一项连挂后实际头部改变而 controller 仍持原接触路线。Basic rebase/adopt 已成功，但 controller 绑定原先要等普通准入路径更新。这不是应放宽物理投影的理由；最小修复应在原 owner 转换成功后立即交接同一已授予路线，不新增授权。

已向实现方提示：现有 `applyCommittedAdmissionRestriction` 先安装绑定，随后返回的 false 可能只是未完成新路线 inspection，也可能是实际异常已经 stall。交接不能因普通 not-ready 反复 rebase/adopt，也不能忽略真实 stall。新建 formation output 尚无 `lastPublishedAtoState` 时，不能将合理的等待误判为发布不变量失败。

复核后续 Dispatcher 修改：从原方法抽出私有 `bindCommittedAdmissionRestriction`，无新状态或授权系统。原 readiness wrapper 仍执行绑定后的 departure gate；新 helper 的 null 仅表示已报告的实际失败。成功 Basic rebase 后立即用显式 `run.admittedRoute` 绑定新轴；formation grant adopt 后同样立即绑定新车身路线，均先于下一控制周期。前者 null 时退出，后者 null 时终止 handoff；普通尚未 inspection 不会反复 rebase/adopt。readiness wrapper 对尚无发布的状态返回 false，不误报异常。未发现本次改动扩大 Basic grant 或跳过物理授权检查。

新 cursor 的逐帧去重与前缀保留已补直接 controller 回归：`physical occurrence survives same frame refresh and frozen prefix extension` 使用两个真实 Core 半圆轨道构成回环，实际移动一整圈后再前进10米，要求 headTraversal 至少包含三段。它在同一 Core 帧上规划两次，再刷新原 binding、追加同前缀 route，逐次断言相同真实 occurrence 的授权剩余距离。若重复累加 frame traversal 或用初始选择锚点重置 cursor，会选择错误圈次。测试使用既有 world fixture 注册/关闭入口；这不是用循环几何替代真实车体的 mock，也不是全局动态验收。

## 最终静态结论

本批代码和对应规范未发现剩余阻断项。信号端点、控制器物理位置、Dispatcher grant 交接和发车 readiness 的职责一致；没有恢复假终点、全路线申请兜底或新增容差。此结论限本批变更及所列调用链，不覆盖此前明确后置的审查项。

## 验证状态

- `git diff --check`：通过。
- Gradle：遵守串行构建约束，本 reviewer 未并行启动。实现方报告首轮聚焦 SimpleATO 49 项、SimpleSignals 40 项通过；旧假终点分支的单点 mutation 导致 5 项失败，恢复后 49 项通过。首轮结果本身不覆盖随后发现的真实生命周期问题。
- 生命周期补修后6个相关测试类220项通过；新增回环 cursor 后控制器与连挂/选站子集38项通过（实现方执行）。reviewer 独立读取过新生成 XML，确认 `TimetableDispatcherTest` 61项、`TimetableFormationDispatcherTest` 31项、`SimpleATOTest` 34项和 `FrozenMissionRouteAssemblerTest` 15项均为0失败；此时 cursor 测试尚未加入，最终38项结果来自实现方，不混称同一次 XML 验收。
- Kotlin/Java 编译已由上述串行测试任务通过；改动模块未配置独立 ktlint/detekt 门禁，格式检查使用 `git diff --check`。主会话最终全套构建/原始全局回放仍在进行；其结果另补，不声明整体模拟成功。
- 无提交、部署、远程修改、容差兜底或审查范围外修复。
