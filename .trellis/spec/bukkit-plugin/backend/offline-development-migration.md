# 旧开发服的一次性离线迁移

## 范围与工作方式

`CURRENT`的一次性离线工具。旧ownership转换已在真实停服副本验证并应用；显式端点模式的本地验证和远端应用是独立事项，不代表游戏内验收完成。源码位于`scripts/offline-world-migration/`，由BukkitPlugin独立的
`offlineMigration` source set编译；不进入插件JAR，不由启动或正常reader调用。
负责已审计开发服的旧split文件转换、显式端点离线转换和当前读取器验证；不提供自动启动迁移或运行时旧格式reader。
实际执行与验收归[Stage 2实施记录](../../../tasks/09-18-signal-addon-implementation/implement.md)。

迁移先正常停服，打包整个`plugins/JavaRailways/`及匹配旧JAR到配置目录之外的独立恢复目录，
校验归档及停服源文件清单；再从归档取得独立副本。工具默认只读dry-run；所有文件均成功转换、严格解码并真实恢复后，
才允许输出到全新目录。源文件和恢复归档始终不改写，也不生成逐文件同目录备份。

## 转换与验证

- 基础设施复用`RailInfrastructureMetadataMigration`：旧outer1/Basic1直接拆分到当前outer2/Basic2/SimpleATO1，
  Destination和Line进入SimpleATO，公共方向进入Basic。Core连接先经下方端点转换，再由当前严格reader验证，不改变约定版本常量。
- 运行时只支持本次审计确认的空列车、空timetable/rolling-stock、空dispatcher clocks/runs。
  旧outer1/Basic1抽离到当前outer2/Basic3/SimpleATO1；保留dispatcher其他checkpoint标量、所有revision和epoch。
  非空旧运营数据明确拒绝，不静默清空，也不假称完成通用迁移。
- 每个current及既有`.bak`各自配对同名infrastructure/runtime。缺配对、孤立runtime、版本或revision/epoch不符均失败。
  gzip按magic识别，输出保持原transport。未知字段由旧边界或当前严格codec拒绝。
- 用真实`CoreWorld.load`、Basic infrastructure/runtime restore及SimpleATO infrastructure/runtime restore验证所有候选；
  fresh隔离世界不tick、不移动列车、不授予行车资源。非空 dispatcher checkpoint 恢复时，将 applied-plan 依赖注册到
  该 fresh CoreWorld 的生产 `InfrastructureCoordinationRegistry`，使用调用方局部 owner token；引用/owner/revision
  校验真实执行。成功或失败后显式 close 该独立 registry，不解析 live world/Bukkit service。
  原线路因接点修正不可达时，真实 timetable restore 拒绝整个候选，不清除 clocks/runs、不改 runtime 以绕过检查。
- 独立工具在首次严格 runtime decode 前调用生产 `BukkitExtensionDataSerializers.register()`，复用 current 的
  `car_render_state` / `train_render_state` codec；直接 conversion 和 materialize 入口共用该初始化，不实例化 Bukkit 插件。
  未知 type ID/version/字段仍拒绝，不反射或删改 payload；固定非空列车 fixture 覆盖真实 restore、render payload 和 runtime 原字节保持。
- `--validate-data-directory`另外读取当前config/physics及实际train/rail包，使用现有loader，不改模型。
  此类离线加载不证明真实客户端画面或服务器启用；缺真实Paper registry必须报告为验证缺口，不能mock后声称通过。

### 已完成ownership转换后的显式端点模式

`-PmigrationEndpointsOnly`对应独立入口`convertEndpoints(infrastructure, runtime)`及CLI `--endpoints-only`。
输入必须是current outer2配对文件及Core infrastructure2；此模式不运行ownership拆分，也不限制已是current形状的runtime为空。
它先替换Core rail中UUID-only连接字段，再串接下方接点成员转换；Basic/SimpleATO及其他outer值原样保留；输出runtime保持原始字节（包括gzip），
current显式形状和空rail文档通过严格验证后保持原始基础设施字节。模式禁止与`repairRedundantCurvePoints`并用。

`RailEndpointMigration.convert`只在该独立source set中识别旧`nextRailAtA/B`、`activeTrackAtA/B`和新
`connectionsAtA/B`、`activeConnectionAtA/B`。各rail必须拥有完整且一致的旧或新形状；单rail混用、跨rail混用、未知字段或未知version拒绝。
Core旧metadata版本1只供原ownership转换组合使用，不是端点模式扩大输入范围。输出仍为当前Core infrastructure2，
格式政策与具体当前表示归[Core持久化](../../javarailways/core/persistence.md#显式目标端点的保存和恢复)。

转换先完整解码旧curve并建立UUID索引，source位置由其所属A/B决定；对每个目标UUID分别计算到目标A、B的距离，
**必须恰好一个端点在`Curve.TOLERANCE=0.005 m`内**。输出该railId+endpoint，列表顺序不变；active映射到本端对应完整目标。
例如A→target UUID且只有target B共点，得到`{"railId":"…","endpoint":"B"}`。两个端点都在容差内时即使某个更近也拒绝，
不能复用旧reader的距离择优规则。逐连接报告source/target两端，缺rail、重复UUID、无候选、双候选或active不在声明列表时整次失败。
全部候选交给current严格codec及真实Core→Basic→SimpleATO restore检查图对称性等业务规则，验证通过才允许写新输出。

转换为`O(N+E)`，另计curve点解码与真实restore成本；文件编排沿用全批原件捕获、输出前逐文件字节复核及fresh-output，
内存随捕获文件和curve样本增长。不在tick或插件启用路径运行；未完成校验不创建输出，写入中I/O失败保留失败目录供审计。
支持窗口限仍需保留的UUID-only开发数据；用户确认所有保留数据转换后，可另批删除旧形状转换，不能为删除工具而放宽current reader。
远端唯一开发服来源为`192.168.1.230`；本地路径是核对副本，不是运行服务器。应用前重新停写、归档及核对，不能拿在线采样副本当恢复点。

正常案例须验证四种端点、顺序、null与第二active；边界案例须验证原始gzip/runtime字节和已current输入no-op；
损坏案例须验证零/双候选、混合或未知字段、坏active/不对称/版本/配对失败且源字节不变。

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PmigrationEndpointsOnly
# 审阅dry-run后，仅写新的本地输出目录；这不是远端应用命令。
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PmigrationEndpointsOnly -PmigrationOutput=/absolute/new-output
```

### 显式连接到独立接点成员

`-PmigrationJunctionsOnly` / `--junctions-only` 调用独立入口 `convertJunctions(infrastructure, runtime)`。
只接受 current outer2 / Core infrastructure2 配对文件，Core rail 必须全部为旧显式连接形状或全部为含
`joinedEndpointsAtA/B` 的 current 形状；不接受 UUID-only 输入、混合形状、不完整成员字段或未知字段。
版本不变，不在 runtime 添加旧开发输入兼容。原 ownership/endpoint 模式在严格 current decode 前也串接接点转换。

旧显式图先校验 UUID 唯一、目标端点存在、每端至多两个精确且唯一的目标、active 属于原目标、每条边有精确反向声明。
之后仅按已声明连接求连通组件，不按位置或距离合并其他轨道。组件全体写成每端的完整 peer 列表（不含自身）；
孤立端点为空列表。每个组件复用 Core `RailEndpointGeometry.legalContinuations`：逐对验证 5 mm 共点与有效方向，
以归一化 outward tangent 点积 `<0` 派生所有合法通路。同侧腿仍保留成员关系但不互通；公共腿与每条异侧支腿互通，
可补齐原图漏掉的合法通路。合法通路保留原相对顺序，新派生通路按稳定 endpoint 顺序追加。

原 active 仍合法或原本为 null 则原样保留；失效且无合法出口置 null，有唯一出口则确定修复，有多个出口则整次失败并报告歧义。
逐组件、删边、补边、active 修复和汇总均写报告。完整 current 输入仍经严格 decoder 和真实业务 restore 校验，合法 no-op 保留原字节。
不改 rail identity、curve JSON（坐标/采样/弧长）、Basic/SimpleATO 数据、revision/epoch 或列车摆位；runtime 包含 gzip 在内逐字节不变。
仍使用既有全批配对、源字节捕获/复核、fresh output 与真实 Core→Basic→SimpleATO restore，不运行物理或授予资源。
禁止与 endpoint 模式或 curve repair 合用；任一输入失败均不创建输出目录。

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PmigrationJunctionsOnly
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PmigrationJunctionsOnly -PmigrationOutput=/absolute/new-output
```

支持窗口仅限仍需保留的显式连接开发数据；用户确认所有保留数据已转后另批删除，不让 current reader 猜接点。
测试需覆盖同侧双腿保留组但零通路、三腿删错边/补漏边、合法第二 active 和 null、未声明但共点轨道不加入、
active 修复歧义拒绝、坏/混合/current 成员输入、gzip/runtime/source 字节保持、备份配对、fresh output 和 no-op。
真实存档副本 dry-run 与其真实列车 restore 单独记录，不能以空 fixture 代替；不将在线采样副本当停服恢复点。
旧图解析/组件遍历为 `O(N+E)`；各组件成员数为 `k` 时，共点检查、合法路径派生及完整 peer 输出另需 `O(k²)`，
总内存还包括原始文件/curve 样本、目标树和 `Σk²` 成员输出。此成本只在离线工具，不增加 tick 查询或持久缓存。

### 受限的旧曲线重复点修复

默认遇非递增弧长失败。只有显式`--repair-redundant-curve-points`才可删除已审计的相邻冗余点：
两个点均为DISCRETE，position/normal完全相同，arc差绝对值不超过1e-12，各tangent分量差不超过1e-7。
保留前点且不删除原末点；保留第一点、末点、总弧长及全部轨道身份/引用，不重新采样或重新计算线路。
被删除点及其向量必须具有准确的已知字段集合，不能利用删点绕过未知字段校验。
每处输出rail ID、point index、原/保留arc与最大tangent分量差；其他位置/法线/线段类型或超出范围的差异一律拒绝。
这些数值只限定本次离线修复，不放宽Core构造器，也不是新的工程配置或运行时兜底。

## 命令、输出与失败边界

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PrepairRedundantCurvePoints
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PrepairRedundantCurvePoints -PmigrationOutput=/absolute/new-output
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationValidateDataDirectory=/absolute/candidate/JavaRailways
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationValidateConfigDirectory=/absolute/candidate/JavaRailways
```

`migrationOutput`必须不存在、父目录已存在，且真实路径不在源目录内。准备失败不创建输出；写入中发生I/O错误时保留失败输出供审计，
再次执行另选新目录，不能把残缺输出视为可应用候选。转换前后校验所捕获的源文件字节未变。
输出只含转换后的split配对文件和报告，不是完整worlds目录。**不得直接用它替换整个worlds目录**：
先复制完整数据目录，再按报告叠加已验证的转换文件，保留sidecar、模型、脚本及其他文件。
配置转换复用已有physics脚本的纯转换/默认dry-run；本次不调用其会生成同目录`.bak`的`--apply`路径。
文件发现只针对本次已审计的普通文件输入；未将任意源symlink包装成支持能力。实际副本已核对无symlink。

候选在离线内存中完整准备，成本随捕获字节、采样点和真实业务restore访问规模增长；不进入任何tick路径。
正式回写前再次确认目标停服及源清单未变，保留外部完整归档后才安装完整候选。
不自动重启，不将已迁移数据与不支持它的旧JAR一起启动；JAR部署另获授权。

## 必需测试与验收

直接覆盖原始重复点失败、显式受限修复成功、坐标/身份/弧长/revision/epoch保留、源字节不变、fresh输出及拒绝覆盖、
不合格点/未知字段拒绝、非空旧运行数据和配对不一致拒绝。真实副本的dry-run、strict restore、数据数量及文件清单需单独记录，
不能用空fixture绿色测试代替。归档路径/哈希、修复报告、完整候选及回写后清单留在配置目录外，禁止提交用户存档或凭据到Git。
