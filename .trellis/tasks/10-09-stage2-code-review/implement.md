# 执行与审查计划

当前阶段：in_progress。用户于 2026-10-09 批准修复已知问题；2026-10-10进一步批准连续小提交后统一检查，不要求等待全部审查结束才修复已确认项。

## A. 完成审查

- [x] 记录当前 HEAD、根仓与 docs-site 脏状态；加载第一批涉及的 Basic/BukkitPlugin 规范。
- [x] 复核首批问题和文档/边界线索，建立带状态及证据的问题台账；具体修复前仍逐项复核。
- [ ] 检查废弃代码、模块归属与公共 API、重复模型、规范一致性、复杂度和安全兜底。
- [ ] 盘点四类文档与父任务清单，区分已实现、未实现和仅文档过期。
- [x] 输出已知问题、待核实/可延期项和八步计划，用户于 2026-10-09 批准；不把维护性线索直接定为游戏阻塞。

## B. 经批准后分批修复

### 全项目审查扩展（2026-10-09）

用户要求所有模块纳入；以下是后续审查顺序，不是批量修复授权。既有八步修复历史保留；第 7 步尚未完成，不能仅以 SimpleSignals 局部检查结案。

1. [x] 建立覆盖清单：按当前 settings.gradle 模块及仓库横向范围列出子系统；每批记录具体生产入口、关键函数/状态所有者、测试证据、未覆盖部分和检查深度。当前记录见 `research/coverage.md`，不按文件数或测试数计算完成率。
2. [x] Core 首轮完整审查：tick/物理状态发布及线程生命周期、曲线与弧长定位、轨道连接/道岔、列车物理解算、持久化与公共 API；核对不变量、重复状态、算法复杂度、四类文档和测试有效性。审查完成不等于缺陷已修复或模块通过验收。
   - [x] C1 runtime/epoch/publication/lifecycle及Basic observation消费边界静态审查；CR-013至CR-020已记录，未修代码。Core212项（6跳过）、Basic382项强制重跑零失败；新失败路径尚未定向复现，Core/Basic均未完成整模块审查。
   - [x] C2（2026-10-10）闭合完整 Core：136 个生产文件、49 个测试源文件和5个fixture，另查build、Dokka输入、14份Core规范及相关网页/导航。全部文件清单见`research/core-review-inventory.md`；七维报告见`research/core-module-review.md`。6条定向诊断已复现；Core/Basic强制重跑和Core Dokka成功，文档warning仍存在。当时CR-021至CR-034待修、运行时代码未改；后续CR-022处置另记于下。
3. [ ] Basic：从物理观察投影到拓扑/通用寻路，再检查基础设施资源、占用、编辑许可、world 生命周期和持久化；追踪 Core → Basic → consumer，确认权威归属及同 epoch/拓扑的一致性。
4. [ ] SimpleATO / SimpleATOBukkit：ATO 感知/制动与控制、运营方向/停车点、路线与时刻表、资源申请释放、控制权切换，以及平台适配；Dispatcher 大拆仍延期，不妨碍审查其正确性。
5. [ ] SimpleSignals：继续启动/世界加载卸载/停用、异步工作退出、拓扑失效与后台恢复、catalog/model 安装和编辑交错；此前事务与 Wand 检查仅是部分覆盖，CR-010/011/012待修复。
6. [ ] BukkitPlugin / BukkitPluginDev / TunnelBridgeBuilder：宿主服务与生命周期、平台线程/实体模型、命令/工具及扩展 consumer；RailWand 大拆延期，不免除其行为审查。
7. [ ] ConfigLib / Annotation / WebUI / 构建及工具：独立检查各自实现与测试，包含 WebUI 前后端、恢复数据、服务接口和打包依赖，不再仅检查 Stage 2 触及契约。
8. [ ] 横向收口：逐模块检查废弃代码、空源目录、风格/命名/i18n、过度兜底、测试质量及持久化版本；结合源码整理 Trellis/Dokka/docs-site/.codeabs 的职责与准确性。保留 docs-site 既有脏状态，不混入修改。
9. [ ] 汇总覆盖缺口、问题优先级和处置/延期；分别给出全项目审查状态与 Stage 2 游戏测试阻塞结论，不让非阻塞维护项自动阻止游戏测试。

下一完整审查模块仍为Basic；用户于2026-10-10要求下一步先改进Core，整模块审查暂在此处衔接，不以Core修复或跨层抽查宣称Basic完成。恢复审查时覆盖全部生产/测试/fixture/模块文档，重点核对Core→Basic→consumer同一权威来源。

### Core 用户确认与定向评估（2026-10-10）

- [x] 联结/解编确认保留为正式能力；CR-021/030不再作为删除候选，当前缺陷尚未修复。
- [x] 确认目前无仓库外插件；旧事件和座位按潜在扩展价值与状态所有权评估，而非直接按无caller删除。
- [x] 完成[旧事件](./research/core-event-api-assessment.md)与[Core/Bukkit座位](./research/core-seat-api-assessment.md)定向评估；事件初稿删除建议已按后续讨论撤回，不把评估完成记为缺陷修复。
- [x] 用户确认三个事件保留并考虑接入；已同步任务与Core规范，该决定记录批次未改公开接口或运行时代码。
- [ ] 用户检查Core Seat API收口建议，再按决定单独实施；本次未批准删除座位原型。
- [x] 记录开发服`192.168.1.230`的手动存档迁移要求；暂不在JAR打包自动迁移、不连接/修改远端，不据此升级schema。
- [ ] 涉及格式变更前取得真实一致存档副本，以离线dry-run/独立输出及current校验验证迁移；实际远端apply另行授权。

### 下一步：Core 改进批次

以下是建议执行顺序，非一批提交；每批开始前说明目的、方法和不涉及的内容，验证并独立提交，按下方最新授权统一交用户检查。

1. [x] 首批修CR-022：列车构造失败不遗留ACTIVE车端，前置身份校验并成对清理副作用；用真实失败路径断言world/rail索引不变，不改持久化格式。实现及验证见下，提交后等用户检查。
2. [ ] 修加载/恢复一致性（CR-023/027/028）及Core相关生命周期问题；涉及宿主或格式边界的修复单独划批，并先核对真实存档迁移要求。
3. [ ] 分批修LINE投影、碰撞容差与运动速度事实（CR-025/024/026），同步有价值但断言无效的测试。
4. [ ] 联结/解编先给出端部、方向和失败事务方案，再修CR-021/030及关联渲染换属主问题，不改变默认碰撞策略。CR-021解编已完成；CR-030联结仍待单独方案与修复。
5. [ ] 三种事件按保留目标设计接入：先给出阶段、payload、线程、生命周期及验证方案，再实施；复用现有事件/观察机制，不复制物理计算或状态。
6. [ ] 随修复同步受影响契约/KDoc，另批处理确认废路径和低质量测试；未确认的Core Seat处置不混入。

### 后续授权与当前批次（2026-10-10）

用户在CR-022提交后批准修复所有无争议问题，并明确允许连续小提交后统一检查；仍按具体证据修复，不把整个问题台账当作已冻结的技术设计。无争议的加载/几何/碰撞缺陷、生命周期遗漏和测试断言缺口可分批推进；公开接口、Seat去留、事件阶段及联结/解编事务方案先明确再实施。

- [ ] 当前批次CR-023（部分修复）：加载成功发布前验证同一tail→head路径上的rail身份、facing、bogie顺序/弧长间距及车端offset；保留精确合法摆位，不重新寻路/摆车。当前保存的bogie不能唯一恢复车体中心的求解锚点，合法曲线还原已有约10 mm中心差与约6.15 mm车钩残差，因此精确相邻车钩校验继续单列，不加宽5 mm容差掩盖问题。补固定损坏输入及有效reverse/curve/turnout回归；仅改Core内部加载校验/测试，不改JSON形状/版本，不接触远端。
  - [x] 同一路径、bogie及车端部分校验和14项正式回归。
  - [ ] 统一车体中心恢复规则后，补精确相邻车钩几何校验；不以本批路径校验结案CR-023。
- [x] 独立批次CR-024：collision查询复用既有容差扩展桶范围，保留exact overlap/candidate cap，补跨桶5mm内外的真实查询回归。
- [x] 独立批次CR-016：关闭终态和最终publication在既有coordinator状态边界复核，补in-flight worker及忽略中断的processor回归；不混入CR-014 quiesce接口。
- [x] 独立批次CR-025：最近点扫描真实piecewise-linear线段做投影，reverse复用canonical结果，复用既有frame插值；保留CurveException message，不加密stream、不新增索引或改raw-input合同。
- [x] 独立批次CR-027：load和prepared snapshot共用单端点严格解析，A/B均解析后才写；拒绝坏引用/远离端点/重复或超容量/非法active，保留合法null与顺序。运行时remove/structural-undo的合法fallback不变，不声称全world恢复事务已解决。
- [x] 独立批次CR-028：构造/decode共享一次raw-point输入校验，Vector3d缺字段拒绝；同步修Core LINE stream重复末点与Basic ConstraintSolver重复接头，不在decode去重兜底、不加热路径扫描。raw frame要求finite/可归一化/非平行，不额外禁止合法非正交输入；位置相同不等于弧长重复。
- [x] 独立批次CR-018：car render revision按opaque runtime train stream隔离；Core以原子owner token覆盖构造、link和remove，Bukkit region task不读取live train map/cars，换属主后接受new-low并拒绝delayed-old。direct commit不污染published gate，无持久化/版本或link policy变化。

### CR-018 修复与验证（2026-10-10）

- 实现：`CoreTrain`每实例生成process-local UUID；`CoreCar`原子保存当前owner。构造成功后绑定，link在几何更新成功后转移，`CoreWorld.removeTrain`使用exact instance删除并按exact token退休；重复移除旧实例不会删除同ID replacement或其mailbox frame。Bukkit只把UUID带到region task，先验证current published frame对象，再以owner token + per-stream revision gate拒绝迟到帧；direct commit保持独立同步路径。
- 回归：`CoreTrainLinkTest`覆盖构造/link/remove及同ID replacement exactness；`CarFrameRevisionGateTest`按真实old-high → link → new-low → delayed-old顺序证明旧stream不能切回。删除了只用注释声称direct commit行为的低质量测试。
- 最终稳定源码验证：`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :BukkitPlugin:test --offline --no-build-cache --no-parallel --rerun-tasks -Dkotlin.compiler.execution.strategy=in-process`成功，Core 296 tests（6 skipped）、BukkitPlugin 360 tests，零failure/error；随后单独`:BukkitPlugin:shadowJar`成功，`git diff --check`成功。一次在源码仍被审查修改时启动的组合test+shadowJar产生test-fixture `NoClassDefFoundError`，不计正式结果；稳定源码串行重跑已消除。
- 边界：没有Folia实体/客户端现场验证；不修link/split事务本身、不改变默认collision policy、不增加持久化字段/版本或公开add-on API。
- [x] 独立批次CR-032机械项：仅清空文件、无consumer helper/pool、未读临时list及废注释；逐项核实引用与持久化影响，事件/Seat/linksplit和需设计的旧factory/editor另列，不结案整个CR-032。
- [x] 独立批次CR-020局部：将匀速/脱轨/快照测试改为真实epoch及live mutation后的值断言，补对应world资源finally；不增加测试数量，不把6项disabled环线算绿色。其余reader时序、Koin fixture及Basic observation缺口另列，不因局部改写结案。
- [x] 独立批次CR-020 reader时序：两个拓扑事务reader回归捕获具体reader线程，并等待该线程确实进入`railTopologyLock`等待队列后才断言阻塞；删除固定sleep，finally释放writer及关闭future/executor/world。为避免反射，仅增加Core内部锁队列诊断，不成为公开API。
- [x] 独立批次CR-014：既有coordinator状态锁内永久停止新wake并捕获exact ticket，正常完成在途epoch之后才允许最终capture；Core边界连同宿主唯一卸载调用接入，补有界真实worker测试。不是close/abort，不新增超时、通用生命周期框架或正式Core API；7模块强制门禁通过，方法和限制见下。
- 后续单列CR-023剩余恢复一致性、CR-026速度语义、CR-015/017生命周期及CR-020/032局部测试/残留清理；CR-013失败清理见下。若核实需要新的产品选择或迁移要求，标记并跳过，不用猜测兜底替代。

### CR-024 修复与组合门禁（2026-10-10）

- collision查询与区间查询共享容差扩桶helper；索引写入footprint不变，exact过滤、排序与candidate cap不变，机制见[rail occupancy](../../spec/javarailways/core/rail-topology-and-occupancy.md#occupancyrelease与结果矩阵)。3项新增回归在旧实现全部失败；修复后该类11项全部通过。
- CR-024/016/023互不重叠源码冻结后串行验证：定向3类共49项全通过；随后强制重跑Core/Basic，15个Gradle任务执行，`BUILD SUCCESSFUL`（1m19s）。Core48 suites/242项（236执行、6跳过），Basic59 suites/382项全执行，零失败/错误。命令沿用下方非增量/in-process/offline门禁；本结果对应三批组合worktree，不宣称只含CR-024的孤立提交已单独重跑。
- 独立`trellis-check`复核实现/测试及契约；task manifest 11+11项、scoped whitespace检查通过。既有编译warning/daemon metaspace提示保留；无公开API、格式/版本、部署或游戏验证。CR-016/023分别提交并记录，不因组合门禁把CR-023精确车钩问题结案。

### CR-016 修复与验证（2026-10-10）

- 最终publication、metrics、latest-completed及ticket完成在原stateLock内检查RUNNING+exact ticket；外层只加CoreWorld内部read-lock scope，保持既有topology→coordinator顺序，不增加第二锁或公开API。失败/overload迟到返回不能覆盖CLOSED，具体合同见[runtime publication](../../spec/javarailways/core/runtime-publication.md)。
- 新增真实worker及消化中断的processor正常/overload共3项有界回归，旧实现全失败、修复后全通过（PhysicsCoordinatorTest共24项）；独立静态检查与上方CR-024/016/023冻结源码组合门禁通过。此提交只含CR-016代码、测试和受影响文档。
- close不是quiesce，也不回滚早先已发布的train frame或processor外部副作用；CR-014及宿主卸载/构造清理未在此修复。无格式/版本、事件接入、部署或游戏验证。

### CR-023 部分修复与剩余缺口（2026-10-10）

- 复用既有tail→head placementSegments，通过单调cursor验证exact rail/facing与bogie顺序，再以同一路径弧长校验车内间距及两端offset；替换错误的1 m欧氏弦长容差，校验后才恢复derived state。算法及未保证部分由[train physics](../../spec/javarailways/core/train-physics.md#存档摆位恢复与当前缺口)拥有，不重新摆车、不改Car/Train持久化字段/版本。
- 新增14项正式测试：1个固定损坏JSON、8种损坏DTO、直线/曲线AB/BA共4个合法控制及1个非对称道岔。坏输入断言具体异常原因和train ID，以及原world/rail/frame/mailbox保留；合法输入断言精确位置及方向。旧代码红灯阶段只运行其中最初11项，6种坏输入全部被接受；另有1个临时coupler诊断，后已移除。最后补的spacing及两端offset共3项没有单独旧代码红灯，不虚报。
- 定向14项与上方Core/Basic冻结源码组合门禁全通过，独立检查确认临时diagnostic/println已移除。仅此加载校验子项完成，CR-023继续部分修复/P1剩余项，不把通过测试误写为完整编组校验。
- 剩余依据：R=30 m、bogie弧长6 m、合法3车编组在restore前后centre偏移约10 mm，第一车钩残差约6.15 mm，已经超过5 mm；内部车两bogie共同前移1 m仍可能保持顺序和车端。当前SavedCar不包含求解锚点，而运动/换向与restore的centre恢复不同，先解决该一致性，不猜新增格式/宽容差方案。无远端、部署、游戏验证。

### CR-025 修复与下游组合门禁（2026-10-10）

- 直接扫描存储线段并投影，选定arc后仅调用一次既有frame插值；reverse委托canonical nearest，异常message正常传入Exception。算法/复杂度见[curve geometry](../../spec/javarailways/core/curve-geometry.md#33-几何最近点)，未改签名、格式、索引或raw-input校验。
- 6项新增回归涵盖稀疏LINE/三维DISCRETE/端点/零几何长度段/容差/诊断，旧实现5失败；既有reverse测试初次改25.4仍命中dense样本，红灯未失败，随后修正为两点LINE并验证位置/frame/view身份。最终两类13项全通过，不虚报稀疏reverse已经单独红灯。
- CR-025/027冻结源码组合门禁：`--offline --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :Core:dokkaGeneratePublicationHtml`，`GRADLE_USER_HOME=/workspace/.gradle`。36个任务全部执行，BUILD SUCCESSFUL（3m13s）；Core50 suites/271项（265执行、6跳过）、Basic59/382、SimpleATO46/351、Bukkit84/352，零失败/错误。结果对应两批组合源码，不是孤立提交测试。直接消费nearest的运营迁移/Wand/undo既有回归随之执行。
- 独立检查通过，manifest/whitespace通过。Dokka仍有既有缺KDoc/declare链接warning，构建链WebUI报告npm依赖漏洞与大bundle提示，另属后续全模块审查，未执行audit fix/升级依赖或声称全仓合规。无部署/游戏验证；CR-027单独提交。

### CR-027 修复与验证（2026-10-10）

- 复用prepared原有规则抽出一个内部endpoint decoder；CoreRail的A/B全部decode并resolve之后才写live连接，prepared仍保留全图对称检查，load仍通过TopologyStateBuilder。具体输入/错误规则由[Core persistence](../../spec/javarailways/core/persistence.md)拥有，未引入通用恢复框架、格式/版本或runtime compatibility。
- 新增23个参数化执行：8种坏输入分别经过load/prepared、4种端点方向、2种顺序/null/第二支选择、1种B端坏输入时双端保持；既有缺失active-target的损坏fixture改为拒绝并补资源关闭，不削弱live remove/undo有效fallback回归。旧生产代码11项失败（8种bad load、null恢复、双端保持、既有orphan），prepared严格控制全通过。绿灯各项全通过。
- 独立检查与上方CR-025/027冻结源码四模块/Dokka组合门禁通过。无全world原子load或DI资源清理保证，无开发服数据迁移/部署/游戏验证；坏旧开发数据如实际存在，应离线审计后手动修正，不恢复静默丢引用。

### CR-028 修复与组合验证（2026-10-10）

- 生产校验改动前执行新Core input类，实际15项中13失败、2项有效控制通过；该命令未加`--continue`，Core失败后Basic test未执行，不能把Basic compile误记成test通过。随后单独执行Basic ConstraintSolver整类，12项中新增junction回归失败、11项既有测试通过。
- 实现只改Core构造/decoder/LINE stream及Basic共享接头生成；测试保留红灯输入。曲线复制后一次验证≥2、精确零起点、finite严格递增arc、finite xyz及可归一化非平行frame；不要求原始frame已unit/正交，不以位置去重，不加查询热路径检查，不改格式/版本。机制由[Core geometry](../../spec/javarailways/core/curve-geometry.md)与[Basic authoring](../../spec/javarailways/basic/curve-authoring.md)拥有。
- CR-028/032机械项/020局部测试源码冻结后强制重跑：`--offline --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :BukkitPlugin:test :SimpleSignals:test :TunnelBridgeBuilder:test`，cache为`/workspace/.gradle`。首次停在测试编译，快照测试错误使用接口不存在的`forward`；改用既有Core端部location成员后重新冻结并完整重跑，BUILD SUCCESSFUL（3m10s），54任务全部执行。
- XML：Core51 suites/286项（280执行、6跳过）、Basic59/383、SimpleATO46/351、SimpleATOBukkit5/8、Bukkit84/352、SimpleSignals47/485、Tunnel8/31，零失败/错误。共1896项、1890执行；CR-028新增16项全部通过。结果属于三批组合源码，不冒充孤立commit gate；后续CR-014不在此结果中。
- 独立检查确认规则/输入复制/共享decoder、真实junction与机械删除范围，manifest 13+13及whitespace通过。既有deprecated/unchecked、Gradle metaspace、Graal POM被当zip及WebUI npm漏洞/bundle提示未在此修复；未执行WebUI测试、Dokka或游戏验证。严格loader可能拒绝旧重复arc数据，真实开发服样本与离线迁移仍未验证，不添加runtime兼容或声称可以直接部署。

### CR-032 机械清理与验证（2026-10-10）

- 删除5个确认无作用文件：3个空文件/空类的rail/persistence空壳、未使用BinarySearch、空DummyAsset；另4文件只移除Quaternion pool、forwardPositive未读list、CoreWorld未用signals constructor字段及CoreCar废SavedCar注释。向量池、CoreRail实际Signal、current SavedCarV1/CarConfigV1不变；不清理用户生成物或空目录占位。
- 全仓生产/测试/资源配置引用核对及独立检查通过；5项删除均可从Git恢复。CoreWorld implementation constructor参数去掉unused值，仓库调用使用保留的secondary constructor，正式World API、持久化形状及版本不变。
- 绿色证据复用上方CR-028/032/020冻结源码7模块强制组合门禁（54任务、1896项/6跳过、零失败/错误），未为死代码删除新增无行为测试。该门禁不包括随后CR-014改动；没有部署/游戏验证。
- 旧RailTopologyEditor/factory构建链及其专用测试、其余可维护性候选仍待分批处理；Core事件保留、Seat处置与联结/解编设计不在删除范围。导航.codeabs漂移仍属CR-002/033，未改docs-site原有脏状态。

### CR-032 废RailTopologyEditor/TrainFactory路径清理（2026-10-10）

- 全仓核对production、test、resource、反射、序列化与文档引用后，删除旧
  `RailTopologyEditor.splitWithPreparedRails/mergeWithPreparedRail`及它们的两项专用测试。保留RailWand真实消费的
  `SplitResult/mergeEligibilityError`；后者的branch拒绝回归改为直接构造线路拓扑，不再借助已删的split执行方法。
  正式split/merge仍由RailWand在单一outer Core transaction内安装prepared rails、应用exact connection plan并同步metadata/undo。
- 删除零production/serialization consumer的`TrainFactory`、`TrainBuildDependency/CarBuildDependency`、
  `TrainConfigV1/BogieConfigV1`、它们的`TrainConfig/CarConfig/BogieConfig`alias及反射填prototype map的
  `TrainConfigurationLoadTest`。保留current `SavedTrainV1/SavedCarV1/SavedBogieV1/CarConfigV1`全部字段、
  extension payload、serializer、`SavedCarV1.load -> CarConfigV1.buildCar`恢复链和既有current fixtures。这是删除未接线并行实现，
  不是根据测试数量删测试。
- 同步Core persistence/topology与Bukkit interaction权威契约；历史审查记录保留原问题并增加完成状态。
  无持久化shape/schema/version/runtime compatibility改动，不触及link/splitBefore、Seat、事件或其他CR。
- 现有回归分别覆盖prepared explicit-connection plan成功/stale、Core transaction rollback与独立merge eligibility；尚无
  RailWand完整split/merge操作跨Core topology、Basic metadata、render sidecar、玩家状态及undo的成功/拒绝/rollback集成测试。
  被删除的旧editor测试只验证废执行器，不能作为现行管线覆盖；该缺口继续留在CR-020/033，不以本次死代码清理结案。
- 冻结源码使用`GRADLE_USER_HOME=/workspace/.gradle`与`--offline --no-build-cache --no-parallel --rerun-tasks
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行`:Core:test :Basic:test :BukkitPlugin:compileKotlin
  :Core:dokkaGeneratePublicationHtml :Core:jar`，21个task全部执行并`BUILD SUCCESSFUL`（1m39s）。Core 308项（302执行、6个既有disabled）、
  Basic 384项全执行，零failure/error；BukkitPlugin及其SimpleATO/SimpleATOBukkit生产依赖链编译成功，Core Dokka成功。
  生产JAR直接检查确认废factory/dependency/config/editor helper类消失，上述current persistence类及
  `RailTopologyEditor$SplitResult`仍存在。保留既有Dokka未文档化/link、Kotlin deprecated/unchecked和Gradle metaspace警告；未部署或游戏验证。

### CR-020 局部测试改写与验证（2026-10-10）

- 匀速原用例改为100个有界等待的真实50ms simulation epoch，每轮独立计算expected位移，并检查head/tail/每车公开位置及immutable frame、controller/train/frame速度与epoch；不再以两个local累加值自证。
- 脱轨用例沿100m尽头线路实际执行2000m/s×50ms，经过BogieException→CoreTrain→coordinator发布/排队；检查未drain时0、真实main drain后1、重复drain/后续idle epoch仍1，并保留reason/epoch/controller移除及原几何。listener只记录，assertion在外部，避免event bus吞测试错误。
- 快照用例实际改变live位置/lifecycle及真实30m跨段摆位，检查旧value隔离；publication用例本批仅补finally关闭，fixture KDoc改为真实NoOp。五文件共18项（12执行、6个原disabled环线），数量未增加；没有新增runtime接口/测试替代实现。
- 独立检查与上方CR-028/032/020组合门禁通过；首次测试编译错误已用现有`train.head.location.forward`修正后完整重跑，不新增extension/cast。CR-014随后改动不在本门禁内。
- CR-020仍部分完成：本批结束时reader sleep/未证明进入读取路径、coordinator无界barrier、Koin fixture、Basic observation collection/bodyTraversal及publication reverse/derail immutable-frame断言继续跟踪；随后CR-014处理了coordinator的2处无界barrier，其余缺口不因finally结案。无部署/游戏验证。

### CR-020 拓扑reader确定性时序（2026-10-10）

- 两个原回归不再用`Thread.sleep(50)`猜reader是否已被调度。测试使用独立双线程executor，writer在持有拓扑写锁且完成结构修改后发信号；reader先发布自身具体`Thread`，随后调用真实`publishWorldFrame`或`allRails`读取。主测试通过Core内部`ReentrantReadWriteLock.hasQueuedThread(readerThread)`诊断等待该线程确实进入同一锁的等待队列，再断言future未完成并释放writer。
- `CoreWorld.isQueuedForRailTopologyAccess`保持`internal`及`@JvmSynthetic`，只暴露锁队列事实，不提供锁控制、runtime fallback或公共addon合同；选择该最小seam是为了避免反射和任意延时。writer release、future有界等待、executor关闭及world资源关闭都在`finally`兜底。
- 定向强制重跑`CoreWorldRailTopologyTransactionTest`共22项、零跳过/失败/错误；独立检查又补强提交后的revision/具体rail ID inventory断言，并让writer、reader、executor及每个world的coordinator/tick manager清理失败可见且不覆盖主断言失败。冻结源码完整`:Core:test`执行53个suite/308项（302执行、6个既有disabled环线），零失败/错误，9个Gradle task全部执行并`BUILD SUCCESSFUL`。task manifest 15+15及scoped whitespace检查通过；既有4处`RailLocationPersistTest`冗余cast和Gradle deprecated warning保留。CR-020其余Koin/Basic collection/bodyTraversal及publication reverse/derail immutable-frame缺口仍开放。

### CR-014 停止新物理工作后保存与验证（2026-10-10）

- `beginShutdown()`在既有stateLock内永久关闭wake入口并返回exact in-flight/latest-completed ticket；不等待、不取消、不改变RUNNING，使已开始epoch可以正常完成。MAIN/SIMULATION入口拒绝新请求，timer在同一锁内检查资格并分配ticket，关闭入口后静默返回；不能通过start重新开放。Bukkit唯一正常卸载入口改为成功await该ticket后capture/save，最后仍close；不引入第二协调器、超时或公开World接口。
- 补3个逻辑测试/4次执行：真实阻塞worker返回同一ticket并正常完成0.5 m运动，world/train/occupancy同epoch；已启动timer正控制与未启动bootstrap均永久停wake；在途失败保持exceptional/STALLED及前次frame/metrics，不用旧成功ticket伪装安全保存。既有2处无界barrier同步改为5 s有界断言，不为超时单独堆测试。没有单独旧代码红灯执行，不虚报。
- 源码冻结后强制运行上方相同7模块命令，`GRADLE_USER_HOME=/workspace/.gradle`，`--offline --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`；BUILD SUCCESSFUL（3m19s），54任务全部执行。XML为Core51 suites/290项（284执行、6跳过）、Basic59/383、SimpleATO46/351、SimpleATOBukkit5/8、Bukkit84/352、SimpleSignals47/485、Tunnel8/31；共300 suites/1900项、1894执行，零失败/错误，coordinator类28项全通过。
- 契约同步Core publication/DI生命周期及Bukkit生命周期；停止的仅是物理wake，不是所有编辑/通用tick/region task。Core真实并发边界已测，宿主接入经过源码追踪及既有消费测试，但未直接执行真实多owner final-save I/O或Folia场景；异常ticket也不证明所有worker已退出。CR-013异常清理、CR-015部分构造、CR-017 DI释放仍独立待处理，close仍是abort而非quiesce。
- 独立检查无本批阻塞项：3条源码hash与冻结值相同，复核7模块XML/28项coordinator回归及10条scoped diff的契约和状态一致性；manifest 15+15和whitespace检查通过。未为复核启动额外构建或修改源码。
- 未修改schema/version、事件/Seat/联结接口或构建配置，未运行Dokka/WebUI测试、部署、开发服迁移或游戏验证；既有编译/依赖warning保留。前轮成功Gradle daemon生成的667 MiB heap dump已非覆盖式移至ignored `.local/gradle-diagnostics/java_pid576750.hprof`保留，未读内容、未提交、未删除。

### CR-013 卸载失败清理（2026-10-10）

- 正常入口仍先标记unloading并撤UUID lookup；卸载开始时捕获exact owner world，保存及每项teardown通过同一局部顺序执行器分别尝试。首个异常保持主异常，后续异常按发生顺序加入suppressed；无论capture/write、render/tick或marker失败，最后都按exact identity移除旧owner map，避免旧world继续进入主tick或挡住同UUID重载。
- 生产保存仍是`beginShutdown`→成功await exact ticket→capture→等待旧periodic write→写最终split snapshot；没有把失败改为成功、自动重试或空存档。topology订阅、speed processor、三个宿主service、physics、render、tick及异常标记均保持原owner，只将原本嵌套finally改为逐项失败隔离。内部重载仅是测试seam，不新增正式API或通用lifecycle框架。
- 新增2项正式回归：持久化步骤失败作为主异常，同时让render/tick/marker失败并核对suppressed顺序；teardown自身首先失败时，后续tick/marker仍执行，并在final map remove前重绑相同Bukkit key以验证exact identity不会删除replacement。capture与write在卸载协调器中进入同一个持久化回调，失败后的控制流完全相同，因此不复制两项只改异常文本的低质量测试；两种生产异常均由这一边界覆盖。两项都断言physics关闭及对应map/lookup结果。
- 最终源码上完整运行`:BukkitPlugin:test`，BUILD SUCCESSFUL（1m40s），26个task中10个执行；XML为85 suites/354项全部执行，零跳过/失败/错误。最终生产源码同时通过`:BukkitPlugin:shadowJar`，无测试替代运行时代码。scoped whitespace、任务manifest 15+15及Trellis契约复核通过；静态复核合并了上面无独立覆盖的重复参数执行，未发现本批阻塞项。
- 既有Basic unchecked/safe-call、Bukkit deprecated/多余断言、JDK native/Unsafe、Gradle deprecated及WebUI 5项依赖漏洞/大bundle warning未在本批处理；没有Dokka、Folia/客户端、真实磁盘失败或游戏验证，不把离线门禁写成现场生命周期验证。
- 不处理CR-015 factory尚未返回world时的partial-init资源、CR-017 Koin child scope、Folia region task完成或远端数据；无持久化shape/version、事件、Seat、联结或构建配置变化。

### CR-015 构造失败资源归还（2026-10-10）

- `RailWorldFactory`新增窄范围LIFO构造owner：每成功取得tick、physics、render就登记其close；全部restore/materialize/render metadata完成才release并返回。失败保持原异常，按render→physics→tick继续清理，清理错误进入suppressed；调用方无需也不能清理未返回的world。
- `RenderChunkManager`补齐对象尚未返回时的责任：listener注册或initial-player refresh失败会立即close。close逐player隔离清理错误并保证最终尝试listener注销，首错保持主异常；注销函数仅作为internal构造注入点供正式回归观察，不形成add-on API。
- 新增`WorldConstructionResourcesTest`三项与`RenderChunkManagerConstructionTest`两项，验证逆序、异常身份/suppressed、成功移交不误关，以及listener已经注册后的refresh失败撤销。无持久化/version、运行期兼容、自动修复、重试或Koin scope变更；CR-017仍开放。
- 强制关闭增量编译后，5项定向回归全部通过；最终同一源码执行`:BukkitPlugin:test :BukkitPlugin:shadowJar`，27个task全部执行并`BUILD SUCCESSFUL`（2m08s）。XML为87 suites/359项全部执行、零跳过/失败/错误。既有Bukkit deprecated/unchecked、JDK native/Unsafe、Gradle deprecated及WebUI 5项依赖漏洞/大bundle warning保留；未部署、连接开发服或做Folia/客户端验证。
- inline `trellis-check`复核production owner链、构造器不可见窗口、异常优先级、正式测试断言和两份lifecycle契约；task manifest仍为15+15项，scoped whitespace通过。本批没有扩大到CR-017或全仓文档整理。

### CR-017 World context registration释放（2026-10-10）

- `WorldParentContext.attachWorld`改为返回窄范围幂等`WorldContextRegistration`。Koin child以world runtime ID创建、link parent并declare world；attach内部失败自行关闭partial scope。`CoreWorld`只持有/释放该binding，不实现通用`AutoCloseable`，也不越权关闭caller-owned physics/tick。
- Core constructor在coordinator attach失败时回滚binding；`CoreWorld.load`在rail/train恢复失败、world尚未返回时同样回滚并保留主异常。Bukkit factory取得world后立即把binding加入LIFO，失败顺序为render→binding→physics→tick；manager发布失败和正常unload均在service/physics/render停止使用后释放binding，再关闭tick。
- 独立检查补出并修复既有owner索引漏洞：manager在`loadedWorlds.publish`后失败时，旧代码只撤UUID lookup、不撤owner map。现在按exact world identity同时删除两者，迟到清理不会删除同UUID replacement；相关registry测试改为直接验证两个索引和幂等/replacement行为。
- 新增`CoreWorldRuntimeBindingTest`与`KoinWorldParentContextTest`，扩充factory/unload和真实Koin persistence清理测试。无持久化shape/version、兼容路径、部署或远端操作。
- 实现代理的5类定向回归及检查代理的非增量复跑均通过。最终源码强制执行`:Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :BukkitPlugin:shadowJar`，36个task全部执行并`BUILD SUCCESSFUL`（2m41s）；XML合计245 suites/1387项，其中1381执行、6项既有Core跳过，零失败/错误。task manifest 15+15、scoped whitespace通过。
- 独立检查唯一新增发现是上面的owner map撤销缺口，修复后由最终全量门禁覆盖；其余API最小性、Koin create/link/declare/close、constructor/load回滚、factory/register/unload顺序、主异常/suppressed及测试质量均无阻塞项。既有编译deprecated/unchecked、Gradle heap/metaspace、JDK native/Unsafe、WebUI 5项依赖漏洞与大bundle warning保留；未运行游戏/Folia/客户端验证。

### CR-026 Core同epoch速度传播与验证（2026-10-10）

- 保留公开`Train.moveTrainForward(deltaS)`签名；`TrainController`仅对CoreTrain使用内部distance+post-command speed seam。head bogie在移动后按当前train-local rail tangent注入m/s速度，随后沿car刚体和整列连接点传播；公开/manual distance-only路径不从距离猜速度。stationary、collision freeze、derail/运动失败和只换向epoch都清零整列active motion，再由既有epoch barrier连同geometry发布。
- 修复传播时暴露的两个原逻辑错误：Kotlin infix `cross`原表达式实际按错误优先级结合，car中心速度在直线退化为0；角速度原用单位方向丢失bogie杆长。现在显式括号并用实际head→tail bogie chord计算`r × deltaV / |r|²`，保持rad/s单位。机制/限制同步到[train physics](../../spec/javarailways/core/train-physics.md)和[controller](../../spec/javarailways/core/train-controller.md)；无公开API、Bukkit补算、持久化或版本变化。
- `TrainUniformMotionTest`在既有真实100×50 ms用例中逐车/逐bogie检查immutable frame速度（20 m/s对应每epoch 1 m），另用真实coordinator依次验证10 m/s×50 ms=0.5 m、停车清除旧速度、只换向epoch空traversal/零速度及下一epoch沿反向tangent发布-10 m/s。不是live field或local accumulator替代。
- 第一次完整Core运行暴露zero-speed清理仍经过重合bogie角速度公式而生成NaN；改为零速直接清理全部car/bogie motion后重跑。最终`:Core:test` BUILD SUCCESSFUL：53 suites/297项，291执行、6个既有disabled环线，零失败/错误。focused motion/publication类14项（8执行、6跳过）亦通过；task manifest与`git diff --check`通过。尚未运行下游/Bukkit、Dokka、Folia/客户端或游戏验证，等待独立检查决定最终组合门禁。

独立检查补充：原实现只覆盖`TrainController`基类tick；审查确认Basic `RealisticManualController`与SimpleATO `AbstractATO`覆盖tick并继续调用公开distance-only入口，因此仍会按合同发布0，ATO freeze还需显式清除旧motion。该跨模块/受保护API改动不在本次Core检查代理授权内，CR-026在补齐这些production caller前不能标记全量完成。Core范围内已修正：显式placement/maintenance/restore、主线程reverse/derail及无controller epoch清除旧速度；曲线刚体约束统一使用实际bogie chord；为公开frame velocity补充m/s单位KDoc。新增真实coordinator回归覆盖`deltaS=0 + 非零末速度`、controller解除后的清零、曲线切线/角速度/刚体关系、distance-only placement及collision suppression。最终完整门禁记录由主代理在跨模块补齐后更新。

跨模块补齐：`moveControlledTrain`提升为有KDoc的protected subclass seam；`RealisticManualController`提交同一个dynamics result的distance/speed，`AbstractATO`提交同一个actuator step并在预先Stall或异常时显式提交0/0 freeze。`WorldManager`不再携带冗余train speed，`CarExtension`把car/bogie各自frame的world-space m/s统一除20后传给Bukkit模型；车体不再由train speed×模型direction重建另一份速度事实。新增Basic真实manual epoch、SimpleATO真实actuate→exception freeze epoch和Bukkit完整car frame conversion回归。四模块focused命令BUILD SUCCESSFUL：Core 2类38项（32执行、6个既有disabled）、Basic 11项、SimpleATO 1项、Bukkit 2项，共52项、46执行、零失败/错误。完整模块门禁、Dokka及独立检查仍由主代理决定；无游戏/Folia/客户端验证。

最终复核先发现SimpleATO完整套件47项失败：ATO demand和actuator step正确，但motion-only publication用CoreCar/CoreBogie原先共享的dirty标记同时翻转未写入的geometry CommitBuffer槽，bogie位置回退，下一次几何搜索以`BogieException`脱轨。修复为car geometry/motion及bogie location/velocity分别维护dirty状态；几何先完整求解，再由求解后轨道切线生成整列速度，两类状态仍在同一epoch barrier发布。Core回归同时断言零位移非零末速度epoch不改变任何car/bogie frame位置；此前47项SimpleATO失败在修复后消失。

冻结源码最终命令使用`GRADLE_USER_HOME=/workspace/.gradle`和`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`，执行`:Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :BukkitPlugin:test :Core:dokkaGeneratePublicationHtml :BukkitPlugin:shadowJar`，39个task全部执行并`BUILD SUCCESSFUL`（3m39s）。XML合计Core 53 suites/299项（293执行、6个既有disabled）、Basic 59/384、SimpleATO 47/352、SimpleATOBukkit 5/8、BukkitPlugin 89/361，共253 suites/1404项、1398执行，零失败/错误；Dokka与shadow JAR成功。保留既有Dokka未文档化/链接、编译deprecated/unchecked、Gradle metaspace/JDK探测、WebUI 5项依赖漏洞和大bundle warning；未运行Folia、客户端实体运动或游戏现场验证。

### CR-019 无行为拓扑字段清理（2026-10-10）

- `RouteGrantTailRelease`删除未读取的`topologyRevision`、只用于该字段的非负校验和从未产生的`STALE_TOPOLOGY`失败种类；两个生产构造点与测试fixture不再传入该值。KDoc只声明真实执行的world、单调physics epoch、train identity、冻结路线游标和claim release职责。
- 不修改`RailwayStateStamp`、`RouteSnapshot`、准入验证、物理编译、route-bound catalog、registry或受影响路线失效路径。tail release继续消费任意更新topology revision下的同world后续epoch；相关拓扑事务若实际影响路线，仍由准入engine的依赖失效机制处理。
- 既有集成回归`unrelated transaction preserves pending grant tail release and extension of older frozen route`继续覆盖旧冻结路线在无关topology revision提升后完成READY、扩展并释放资源。本批无公开API、持久化、版本、抽象或产品行为变化。
- 实现阶段非增量定向执行`RouteGrantTailReleaseTest` 5项和`RouteResourceAdmissionEngineTest` 43项，48项全部通过。独立检查随后在最终源码上用`--rerun-tasks`完整执行`:Basic:test`，59个suite/383项全部执行，零跳过/失败/错误，12个Gradle task全部执行并`BUILD SUCCESSFUL`（22s）；`compileKotlin`/`compileTestKotlin`、Trellis task manifest与scoped whitespace检查通过。既有Basic unchecked/冗余safe-call、测试冗余non-null assertion、JDK toolchain探测和Gradle deprecated warning未在本批处理；未运行Core测试、下游模块、部署或游戏验证。

### CR-022 修复与验证（2026-10-10）

- 根因：`TrainEnd`构造即注册rail，world身份检查与extension初始化却可能随后失败；调用方只有构造返回后才进入failed-load清理，因此无法取得失败对象撤销注册。旧测试缺少构造期失败后的索引/资源断言。
- 实现：`CoreWorld`共享内部身份校验；`CoreTrain`先验证身份并解析两个端部位置，再注册端部/world并初始化。失败逐项清理本次controller和车端，world仅在成功接纳后撤销；调用方cars不销毁，不复用完整destroy/failed-load清理。合同同步到[train physics](../../spec/javarailways/core/train-physics.md#构造与失败清理)，不新增通用事务或extension回滚协议。
- 定向红→绿：新增正式`CoreTrainConstructionTest`，旧运行时代码10/10失败，修复后10/10通过。四种身份冲突分别检查直接构造/存档加载，另测尾端位置拒绝及extension初始化失败/同身份成功重试；断言精确车端身份、原列车frame/controller及caller-owned cars，不只断言抛异常。
- 完整门禁：以下命令`BUILD SUCCESSFUL`，14个任务全部执行；Core47 suites/222项（216执行、6跳过），Basic59 suites/382项全部执行，零失败/错误。首次定向绿灯遇生成的Kotlin增量缓存损坏，改用非增量/in-process重跑，未删除缓存或修改构建配置。既有编译warning和daemon metaspace提示仍存在，不在本批顺带治理。

```bash
env GRADLE_USER_HOME=/workspace/.gradle ./gradlew --offline --rerun-tasks \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :Core:test :Basic:test
```

- 独立检查：`trellis-check`复核构造注册/清理、相同实例二次add及真实回归断言，未发现代码修改项；manifest的一处extension回滚表述已改为constructor-owned registrations。任务manifest（8+8项）、文档链接及变更空白检查均通过。
- 边界：无公开API或持久化格式/版本变化，无事件接入、Seat删除、编组几何或宿主factory修复；未运行Dokka、opt-in stress、平台测试或游戏验证，未连接开发服。仅CR-022记为修复，不把本批绿色回归记为Core通过或全项目审查完成。

### CR-010/011/012 SimpleSignals准备失败、取消分类与Wand stale回归（2026-10-10）

- CR-010把`Files.createDirectories(worldsDirectory)`移入prepared-world reservation的同一失败释放边界；目录创建、staging删除／写入／复制任一失败都会移除本world reservation，不增加重试状态机。真实临时文件系统回归用普通文件占用`worlds`路径，确认失败后修复路径，并由同一store/world重新prepare、close、save和load成功。
- CR-011保留host atomic source callback内、primary rename前的最后authoring guard，以独立取消标记把该失效分类为`WORLD_UNAVAILABLE`；未设置该标记的真实commit I/O异常仍为`PERSISTENCE_FAILED`。manager回归让第三次guard调用在callback已进入后失败，断言primary文本、runtime publication identity、host source publication identity和atomic commit计数均不变，permit正常关闭。
- CR-012不改已有正确生产清理。原stale-selection测试先让backend真实返回第一点及capture，再令publication失效；安装新的capture后继续点击，通过记录backend每次输入直接断言恢复调用的`firstPoint == null`，不再只以消息和零调用间接声称已清理。
- 完整非增量`:SimpleSignals:test`执行33个Gradle task并`BUILD SUCCESSFUL`（3m13s）；XML合计487项，零跳过／失败／错误，production与test Kotlin/Java compile均执行。首次3类定向运行134项时，新CR-011测试错误地对合法空source publication调用`single()`而失败；改为比较完整source list identity后，单项及完整模块门禁通过。
- 独立检查补强成功prepare后的reservation生命周期断言；冻结源码完整门禁仍为487项零失败／错误／跳过，task validate与变更空白检查通过。无持久化shape、schema version、runtime compatibility、公共API或协调器变化；未部署、连接开发服或运行Folia/客户端测试。既有Java toolchain探测、Kotlin读取Graal POM、deprecated/unchecked、WebUI 5项依赖漏洞／大bundle和一次测试期physics overload日志保留。

### CR-021 解编事务修复与验证（2026-10-10）

- 语义：用户确认收口为`splitBefore(carId,newId)`。`Train.cars`是不随reverse重排的稳定编组/持久化顺序；目标car之前的稳定前缀留在原车，目标及后缀进入新车。未知/首车拒绝，末节前合法。公开`isReversed`与只读派生`operatingCars`表达当前运营顺序；两列继承原方向，因此同一car ID在正反向分割后仍得到同一稳定两半。
- 机制：after-commit任务先等待exact physics ticket，再验证边界和新ID/name，严格解决两半端点/路径并预生成原车body traversal、occupancy与frame，然后按slot prepare extension并构造新车。新车构造失败复用已验证的constructor cleanup；成功后到原车发布之间是commit-only区间，只应用预计算字段、清零无controller的新车car/bogie运动、翻转CommitBuffer并更新端点队列/原子frame/mailbox，不再求解几何、调用extension或检查身份。两列结构frame沿用切分前同一已完成physics epoch；转移cars由新train stream取得，未销毁car，原controller保留。
- extension边界：`splitBefore`必须返回独立实例且不修改原extension/外部世界；返回实例的首次initialize仍属prepare，不得修改共享cars或发布平台效果。Core没有伪造通用plugin rollback，该合同写入`TrainExtension` KDoc和[train physics](../../spec/javarailways/core/train-physics.md#解编事务)。
- 回归：`CoreTrainSplitTest`的12项覆盖正/反向对同一car ID的稳定两半及各自operating view、末节单车、未知/首车、重复car ID、重复train身份、两半各自的端点/路径不可解、extension prepare/initialize失败，以及移动中切分的同epoch/新车零运动帧；断言对象身份、方向、controller、extension slot、frame、world/rail索引、mailbox及publication owner，12项全部通过。
- 公共API及完整门禁：冻结源码非增量强制重跑`:Core:test :Basic:test :BukkitPlugin:test`，并编译`SimpleATO`、`SimpleATOBukkit`、Bukkit生产源码和内置Java signal API consumer，生成Core Dokka；35个Gradle task全部执行并`BUILD SUCCESSFUL`（2m23s）。Core 54 suites/311项（305执行、6个既有disabled）、Basic 59/384及BukkitPlugin 89/361全部执行，合计1056项、零失败/错误。另用临时Java consumer实际调用`getCars()`、`isReversed()`和默认`getOperatingCars()`通过`javac`/`javap`。Dokka、Kotlin与WebUI仅出现既有warning；未将warning、WebUI依赖报告或bundle尺寸顺带纳入CR-021。task validate与`git diff --check`通过。
- 边界：删除未发布无caller的整数接口，不保留compatibility alias、不升持久化版本；不改碰撞/link策略、持久化shape或runtime compatibility，不实现SimpleATO可分割面元数据/规则。没有部署、Folia/客户端或开发服验证。CR-030 link事务仍开放；CR-033不再跟踪cars顺序语义。

以下八步于 2026-10-09 获用户批准；第 1、3、4、5、6 步及第 2 步根仓入口文档批次已完成。剩余文档问题留到全仓代码审查，第 6 步提交后等待用户检查，再推进第 7 步。步骤不是提交数量，每步可拆成独立可验证的提交。

1. [x] **无调用原型清理（CR-001）**：`6cf5805d` 已获用户认可，删除三个 Java 原型及 Lombok 残留。
2. [ ] **文档与阶段清单对齐（CR-002）**：先说明 Trellis/Dokka/docs-site/.codeabs 分工，修正过时 owner、JS 规则和完成状态。根仓与 docs-site 分批提交；导航在后续源码变更时同步更新。
   - [x] 根仓入口：文档职责规范与索引、requirement方向归属、模块表、SimpleSignals README和父任务当前状态汇总；纯文档校验和独立复核，未重跑JVM测试。
   - [ ] 延后至全仓代码审查：剩余owner spec、docs-site和代码地图整理。后续代码变更仍同步直接受影响的契约，不进行独立文档整顿。
3. [x] **旧寻路/观察路径清理（CR-003）**：`9ab81d46`和纯空行修正`9f6aac89`已获用户继续确认；World getter与fixture迁出pathFinding，保留有效身份冲突回归并新增3项访问器测试，受影响模块1601项测试零失败。
4. [x] **metadata 模型收口（CR-004）**：业务及测试统一使用四个分组，删除平铺 constructor/getter/custom copy 和 RailMetadata alias；迁移映射保留在一次性迁移边界。轨道重建共用局部函数，仅替换 CURVE 与 curveSafety，保留其他分类及未修改组；不改格式/版本。
5. [x] **公开 API 收口（CR-005）**：四个现有路线值迁入 basic.api.route，迁移公开签名与 consumer imports；算法、内部结果与快照转换仍隐藏。不复制模型、不改变行为/持久化，复用既有集合隔离测试与 Java consumer；下游回归、Dokka 页面及模块打包门禁通过。
6. [x] **运营编辑适配器迁移（CR-006）**：Destination 角色字段/动态表单/会话初始化与解析/保存删除及4项既有测试迁入 SimpleATOBukkit；宿主保留选点、PDC、编辑许可、调度、消息与标记。新增2项真实 service 回归并改用生产UI注册与独立预期字段；必要 Basic 编译依赖与单向门禁同步，测试/打包/边界门禁通过，不改存档或版本。
7. [ ] **局部一致性与复杂度治理（CR-007/008/009）**：基本代码约定、玩家文案先行；重点审查 SimpleSignals 事务/编辑职责，确认后小批拆分。RailWand/Dispatcher 非阻塞大重构允许延期，但须记录归属和理由。空源目录清理单独核对，不碰用户生成物；兜底只按证据调整。
   - [x] SimpleSignals 定义事务与 Wand 编辑专项只读审查：记录 CR-010 保存准备失败释放、CR-011 取消状态分类、CR-012 stale-selection 测试断言缺口；未修改运行时代码或运行测试。RailWand/Dispatcher 拆分建议已记入根 TODO，非游戏测试前置。
   - [ ] **低质量测试清理（用户追加）**：按 PRD 第 8 项审查，删除无有效覆盖的测试、合并纯重复项，修正有价值但断言无效的测试；逐项说明理由与保留覆盖，保护安全不变量和历史 bug 回归，小批提交并验证受影响模块。
8. [ ] **整体复核与正式游戏测试交接**：按台账核对处置/延期，运行受影响模块与边界门禁，修正父任务剩余项，输出真正阻塞正式游戏测试的问题和现场验证清单。不在审查任务内自动部署。

每批开始前说明目的/方法，完成后单独验证、提交并打开预览；当前按用户于2026-10-10确认的节奏连续小提交，最后统一检查。若后续复核发现需要改变产品行为或扩大重构范围，先修改计划并交用户确认；不因总体计划获批就自行扩张。

## 验证与关闭

- 任务文档：`python3 .trellis/scripts/task.py validate .trellis/tasks/10-09-stage2-code-review`、`git diff --check`。
- 代码改动按 `.trellis/spec/javarailways/shared/quality.md` 选择 touched modules 及直接 consumers，Gradle 使用 `/workspace/.gradle`。
- 公共 API/模块边界改动检查下游编译、Dokka、Java consumer 和现有模块打包门禁；平台改动补充调度/线程归属审查。
- docs-site 改动在其独立工作树验证；不把既有修改混入提交。
- [ ] 汇总问题处置与验证结果，核对父任务未完成项，列出正式游戏测试前剩余阻塞和现场测试清单。
