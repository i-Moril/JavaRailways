# 关门完整完成与实际车辆准备提前量 — 2026-10-31

用户授权继续修复；沿用此前不新建Trellis任务的决定。用户随后授权提交本批；不部署、不归档，服务器数据未访问或改变，无关heap dump保留。

## 改动边界

原先默认提前20tick，同时CarBehavior把最晚动画效果启动当作操作完成，既不足以适配不同车型，也不能在晚点时保证关门动画完整结束。新默认 preparationLead 为实际列车关门耗时叶子，可与现有数字运算组合额外准备时间。运行器仍是既有Dispatcher/StationStopProgramRuntime，不新建函数执行器或一般运行期JS解释器。

Basic Gateway提供只读异步关门耗时；LIVE/SIM按本次真实车厢、相对方向和门侧取得最大正常关门时长。behavior.js的door函数返回YAML动作名列表，实际执行与估时使用相同隔离只读规划路径，不能通过实时persistent JS试执行探测。计划按不可变来源/能力缓存；其他动作/事件仍保留每车persistent Context。无脚本模型保留其既有确定映射。新模型契约只接受当前输入，没有旧门协议兼容或专用旧输入拒绝分支。

CarAnimationPlayback使用真实模型转换后的逐part时间轴；CarBehaviorClock在既有TickManager中每车初始化注册一次，统一延迟与动画计算，空闲不运行JS。可见模型只投影同一时钟的当前/最终frame；隐藏、重新可见和模拟共享时序。最后一帧经过owner commit后才完成效果。源码切换与逻辑关闭先脱离槽位再销毁，取消只结清future/clock注册，不在world执行线程触及Bukkit视觉。可见性重建不重置同来源播放进度。

估时分别对启动延迟和播放时长按20tick/s量化再相加，所有并行效果/车辆取最大值。真实S100六种车厢、两种运营方向和左右/双侧验证正常关门38tick（0.6秒警示延迟+1.3秒门动画）；0.06秒延迟+0.06秒播放需4tick。实际ticket仍单独确认完成，不靠估时强制成功。始发首次关门不虚构先前开门，可能因车门已关而实际更快，仍等待真实警示动画。

运行期查询只在非终到、有门且表达式依赖实际耗时时启动一次，pending/失败/非法值/超时/取消走既有停车失败链。固定表达式、无门及终到不查询。实际Dispatcher测试覆盖发车100/关门40时于60开始；到100但ticket未完成不能移动。到达主分支与子任务、最小停站、发车时间、门关闭和资源门禁保持。

## 格式与迁移

作者文档、工作区及编译/运行快照采用 preparationLead 数字表达式，不再写旧数值字段。叶子{doorCloseTicks:true}，常量{value:N}，数字operation仅支持既有数字算术；16层/4096节点，最终非负安全整数。未发布格式保持约定版本，不添加运行时upcast或旧字段识别。

独立 migrate_station_preparation_lead.py 只处理编辑文档的旧数值，保留为同值字面量，既不会把手填20猜成默认，也不修改普通JSON中的同名键。dry-run固定源/candidate/helper哈希，apply要求停服、外部恢复、权限保留、原子替换及失败回滚。已生成正式JS、世界运行快照和服务器模型包须在部署授权后另行盘点，不能把作者文档工具冒充全服迁移。未在本轮修改服务器存档。

仓库S100源行为与分发zip已同步；zip仅更新behavior.js及其依赖的actions.yml，其余62个条目内容保留。压缩包经真实PackageLoader解包/加载并验证六种车型关门计划。

## 验证

前端420测试及TypeScript/Vite build通过。真实当前dist完整应用+本地mock存储浏览器验证默认叶子、鼠标断开/重连及无效草稿恢复、ADD+耗时+10拖拽、真实保存请求AST、刷新重开、改15撤销10及中文切换；普通WAIT输入误接运行期叶子显示错误并禁用保存。不是live后端或游戏物理验收，浏览器及fixture服务已关闭。证据/tmp/jr-door-browser。

平台聚焦72测试通过后补入始发首次关门和逻辑关闭禁止投影的回归，最终完整门禁覆盖。独立审查发现并修复逐阶段tick量化偏差及清理时跨位置owner投影。完整联挂回归原先仅推进到650ms启动点；现按绝对时钟在650ms验证仍pending、1300ms确认完整警示结束，并验证联挂后的原车JS状态/固定目标集合。

最终`./gradlew :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :WebUI:test :BukkitPlugin:shadowJar --offline --no-daemon -I /tmp/jr-door-full-tests.gradle`通过。SimpleATO整套单JVM末尾OOM，改为临时2GiB并按class隔离后全套通过；不改变产品或服务器配置。JVM2422项通过，另1项既有opt-in实验跳过，分别Core538、Basic436、SimpleATO673、Bukkit709通过+1跳过、WebUI66。新离线转换10测试及既有两套12+12测试对候选JAR通过。模型脚本node --check、打包内嵌WebUI哈希及git diff --check通过。最终日志/tmp/jr-door-final-gate.log，机读摘要/tmp/jr-door-validation.json。

用户已授权提交本批代码；部署及Folia游戏客户端验收仍未进行，不能从本地逻辑/浏览器验证声称线上车辆视觉已验收。后续牵引／制动模型TODO和隔离性能实验不纳入本批提交。
