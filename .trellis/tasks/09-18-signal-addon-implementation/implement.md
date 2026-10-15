# Stage 2 implementation plan

## 当前收尾批次（2026-10-14，Core收尾后恢复Stage 2）

2026-10-15正式游戏准备已核对，当前基线与后续顺序见[就绪清单](research/game-test-readiness-2026-10-15.md)：建造交互离线完成；用户单独批准的Basic CR-040和随后CR-041现已修复，最新五模块2157项回归通过，证据归[审查任务批次](../10-09-stage2-code-review/implement.md#cr-041配置ui收口2026-10-15用户单独批准)。需正式规则包而非smoke规则；本地旧配置/世界不能直接换JAR启动，远端现状未刷新。未部署或完成游戏验收；动态贡献依赖触发缺口单列审查台账，不冒称所有配置场景均完成。历史未勾选框与早期研究保留当时上下文，不再当作当前功能计数。

后续顺序更新：用户于2026-10-15批准以下范围和执行顺序，测距放在其余建造交互之后。进路删除/保护主信号重绑、表示器新建/映射/入口编辑、实例参数数值编辑、边界/区间编辑叠加及独立测距已接线并通过各自离线门禁；本轮建造交互清单已完成离线实现。接下来核对已有Stage 2验收项及正式版游戏测试准备，不将未来平台拆分、任意样式编辑等加入本轮前置条件。未部署，真实游戏验收未完成，父任务保持in_progress。

用户另于2026-10-15要求后续将SimpleSignals领域逻辑与Bukkit解耦，已创建独立[平台拆分任务](../10-15-simplesignals-platform-split/prd.md)。目前仅做简要评估并记录为planning，不切换当前任务、不立即重构，也不把它加入本轮游戏测试的前置条件。

边界显示已确认仅为编辑辅助：持建造工具时向编辑者显示，离手/上下文失效后清理，不创建面向所有玩家的常驻边界模型；沿用现有玩家隔离与显示生命周期。

实例参数编辑已确认首版使用设置页数值输入（三轴偏移、旋转角度、跟随轨道倾斜），不做鼠标拖拽；复用现有摆放计算并保持逻辑绑定，进一步交互优化延后。

进路删除与重绑决策已确认并落实保护主信号交互：保留相关设备，失去有效进路绑定后不可放行；采用“选已有设备 → 选新入口/进路 → 确认”，只改绑定、不移动模型，进一步交互优化延后。非空未知引用仍拒绝，不沿用之前建议的级联删除。表示器跨入口重绑已随映射编辑实现。

表示器缺失映射决策已落实：允许保存进路，未映射进路被选中时使用明确配置的无进路外观表示没有进路指示，编辑页提示补配，其他映射继续有效；不猜测variant。后端及界面均已通过离线门禁，真实客户端尚未验收。

### 建造交互实施顺序

每批先补充对应owner契约与必要伪代码，再实现、验证并作小提交；不一次堆积全部交互。精确API及持久化表示按源码确定，不在此规划猜测字段。复用现有manager编辑事务、Wand设置页、实例摆放计算与显示生命周期，不增加第二套管理器。

- [x] 进路删除与保护主信号重绑：梳理有效/失效绑定的候选校验与输出，保留设备和附属关系，提供明确的重绑交互；验证删除后不能误放行、无关进路不受影响、重绑不移动模型及编辑失败不半提交。表示器跨入口重绑归下一项。
  - [x] 第一笔先实现后端：明确null绑定、删除时规范化保留设备及映射、保留空表禁行，并验证持久化/输出/严格校验；具体合同见[工具规范](../../spec/simple-signals/backend/section-authoring-wands.md#删除进路保留设备与显式重绑)。不宣称已有玩家交互。
  - [x] 第二笔接Wand删除/保护主信号重绑，复用设置页、精确修订和原事务；独立复核及离线门禁通过。
    当前范围：Route Wand新增Shift+右键已保存进路列表和删除确认；Signal Wand设置页选择已有保护主信号（含未绑定/ground）、有效新目标并确认重绑，只改逻辑绑定，不改PDC。表示器跨入口重绑与下一批映射编辑一起处理；精确交互和测试归[owner合同](../../spec/simple-signals/backend/section-authoring-wands.md#wand删除进路与保护信号重绑)。
- [x] 表示器新建/映射编辑及跨入口重绑：提供模型支持的进路外观选择，允许缺项并显示补配提示；验证未映射进路使用明确无进路外观、已有映射正常、非法模型variant仍被拒绝。入口重绑须同时明确映射，不能猜测外观。
  - [x] 第一笔后端：映射改为入口进路的子集，未配输出使用明确noRouteModelVariant并保留selection身份；增加最小模型variant目录投影，不修改renderer/宿主API。机制归[工具合同](../../spec/simple-signals/backend/section-authoring-wands.md#表示器映射作者与新建)。
  - [x] 第二笔Wand：新建意图与既有身份区分，捕获位置后明确配置并确认才创建；设置页编辑既有表示器/新入口和映射，不移动模型。复用原事务及对话guard，不创建未摆放占位定义或另一个草稿仓库。
- [x] 实例参数数值编辑：三轴偏移、旋转角度及跟随轨道倾斜设置接入既有摆放流程；验证选中对象身份/修订、几何计算和逻辑绑定保持，不实现拖拽。ground编辑绝对世界XYZ且无轨道倾斜；具体接口和失败边界归[工具合同](../../spec/simple-signals/backend/section-authoring-wands.md#实例参数数值编辑current)，离线通过不等于客户端验收。
- [x] 边界及区间编辑叠加：复用既有玩家隔离显示机制，补齐位置、方向及区间提示；验证离手、退出、切世界及卸载后清理，显示不参与行车逻辑。完成证据见下述批次记录；真实客户端另验。
  - [x] 前置公开文字API：BukkitPlugin提供EditorTextService/Handle/Label、玩家隔离、完整列表更新与宿主调度/关闭完成结果，独立复核及联合离线门禁通过。
  - [x] 已有边界/区间几何和持工具生命周期接到该API，真实客户端验收另记；不启动后续平台模块拆分。
- [x] 最后实现独立测距：直线与沿轨距离，沿轨采用物理最短路，不依赖当前道岔选择，不实际扳岔或申请行车资源。
  - 2026-10-15完成独立复核/离线门禁。复用现有Basic查询图/Dijkstra，通过最小公开`PhysicalRailDistance`入口隔离内部类型；同点/合法公共端点允许0，不改变运营路线零长拒绝。交互与生命周期归[测距合同](../../spec/simple-signals/backend/rail-measurement.md)，Basic内部方法归[路线合同](../../spec/javarailways/basic/route-planning.md#只读物理测距)。不增加信号定义依赖、显示循环、schema或资源操作；真实客户端操作待验。

#### 独立测距验证（2026-10-15）

- 九个产品/测试文件：Basic新增PhysicalRailDistance公开入口和4项API测试、既有TopologyRoutePlanner增加仅返回权重的internal seam；SimpleSignals新增RailMeasurement后台和MeasurementWandHandler，接插件命令/生命周期与工具usage，并新增2项后台和5项交互测试。共11项新增测试，不复制现有Dijkstra或新建资源机制。文档同步Basic API/算法/Dokka导览、信号工具及README（同时纠正前批已实现叠加/表示器交互的陈旧使用说明）。
- 新API通过现有unrestricted QueryGraph/Search求权重，合法零长直接返回距离而非构造RouteSnapshot；原运营plan仍拒绝零长。沿轨查询不读取运营限制或实时道岔选择。玩家owner捕获工具/世界和O(1)拓扑引用，worker处理曲线和搜索，最终owner复核exact Player/World/session/root；不依赖信号definition runtime、不设tick任务。定义/资源/存档和版本均未变。
- 独立复核修正PrimeCurve静态类型造成的PersistentBy编译依赖泄漏，显式以现有公开Curve接口接收，不增依赖；扩展既有交互失败用例，验证工作完成后、回复前宿主world失效的诊断/清理和恢复后重新选点。初轮Basic4项通过，但Signals产品编译因上述类型失败（1分5秒）；修复后定向门禁33秒通过。没有为测试放宽生产边界。
- 隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`，Java25、`GRADLE_USER_HOME=/workspace/.gradle`，最终执行`:Core:test --rerun :Basic:test :SimpleSignals:check :BukkitPlugin:shadowJar :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :Basic:dokkaGeneratePublicationHtml --offline --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m' -x :WebUI:installFrontend -x :WebUI:buildFrontend --console=plain`。1分3秒，42task中10执行32up-to-date。实际重跑Core58套409项、Basic60套399项、Signals53套562项，共171套1370项，失败/错误/跳过0；本轮已编译产品/测试，最终复用未变编译产物并编译最新handler测试。shadowJar、Javaconsumer、模块/JAR边界与Dokka实际执行。Bukkit/WebUI测试、前端及游戏运行未执行。
- Dokka生成PhysicalRailDistance index/between页面，已人工核对公开参数、米制返回/null及异常说明，新增API无相关warning；现有UI等未文档化/失效链接警告保留，不声称全Basic文档无警告。scoped git diff --check通过，无独立lint任务。
- 最终全部src相对路径排序文件清单摘要：Basic `0400e023c7a5e547884168bcf2dd528f65bb9a9f6abb7a5ce9e06022da0b5d0a`，Signals `f6362f88a0fe8d163f2af916dadc7ec7ddcf4a33d5776e3078808dd96791a60c`，工作区与已测候选一致。最终日志`/tmp/javarailways-measurement-final-check.log`，定向日志`/tmp/javarailways-measurement-focused-check.log`，XML/Dokka/两插件JAR在候选对应模块build目录。不部署、不宣称真实Paper/Folia或客户端已验收。

#### 边界叠加前置发现（2026-10-15，公开能力方向已确认）

开始本批前Section Wand只有点击后的警告显示，Route Wand已有独立粒子预览；需要完整的持工具自动显示、文字及关闭清理。已复用RoutePreviewDrawing的几何采样、20tick/512点分批，不新增拓扑搜索；附近筛选使用已准备值，准备成本随作者定义/显示点数量增长。
实现前发现TextDisplay停用清理的生命周期接缝：附属插件停用后不能依赖自己的调度任务完成Folia实体删除，PluginDisableEvent也不能保证排队任务不被取消。现有宿主ModelPresentationService提供host-owned调度和可观察closeOwner完成结果，但只支持普通模型，无文字/编辑者隔离接口。不能通过私有实现导入、借用宿主Plugin对象调度或改成粒子/聊天替代完整文字来隐瞒问题。
用户已同意BukkitPlugin公开Wand、Rail可视化等通用平台能力，方向归[编辑标记规范](../../spec/bukkit-plugin/backend/editor-markers.md#已确认的公开能力方向target2026-10-15)。该前置发现已通过下述最小临时文字API解决；不加入区间/信号语义，不一次重构全部Wand。边界消费者在后续独立批次接入，见下述验证记录。

用户已批准[首批文字API](../../spec/bukkit-plugin/backend/editor-markers.md#首批临时文字-api)：先独立实现公开service/handle/不可变标签值、宿主调度/清理及Java消费与平台回归，再独立接边界叠加。采用完整标签列表、稳定显示身份及只保留最新待显示状态；本段记录开始前的批准基线，具体完成证据另记。继续使用本任务，不新建重复任务；后续平台拆分任务仍为planning。

#### 边界与区间叠加接线验证（2026-10-15）

- 七个产品文件：新增SectionOverlayDrawing/RuntimeSectionOverlayBackend/SectionWandOverlay，复用RoutePreviewDrawing采样与箭头、SectionWandHandler物品识别；HostServices取得必需EditorTextService，插件接持物会话及模型/文字联合关闭。四个测试文件（其中三个新suite）覆盖几何、真实runtime基线失效、持物/玩家隔离/关闭及必需服务。具体算法与成本归[工具合同](../../spec/simple-signals/backend/section-authoring-wands.md#持工具的边界与区间编辑叠加current)。无Core/Basic/ATO、持久化字段或版本变化；不实施后续平台拆分。
- 独立复核修复同UUID重连的旧Player回调可能清理新会话的问题：会话绑定具体玩家实例，并验证旧排队刷新、退休及退出不能关闭新显示；强化不可用标签断言，避免空集合使all断言无效。没有新增generation框架或业务规模限制。首次产品编译发现interior字段名误用并修正；首次测试编译发现函数列表last解析失去函数类型，改为显式索引后通过。
- 初步定向六套32项通过（18秒），随后复核修复加入最终完整门禁。同一隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`，Java25与`GRADLE_USER_HOME=/workspace/.gradle`，执行`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries --offline --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m' -x :WebUI:installFrontend -x :WebUI:buildFrontend --console=plain`。59秒，34task中8执行26up-to-date，51套555项实际重跑，失败/错误/跳过0；产品/测试编译与模块/JAR边界实际执行，未变Java消费者和宿主shadowJar复用前序产物。另scoped git diff --check通过，无独立lint任务。
- 最终全部SimpleSignals/src相对路径排序文件清单摘要`b8230b9373eb11548b66e15c1530db46b54b96bbc4f2dbc50cbecf6b9c6fc7b5`，工作区与已测候选相同。日志`/tmp/javarailways-section-overlay-final-check.log`，XML在候选SimpleSignals/build/test-results/test。Bukkit源未变，未重跑其测试或Core/Basic全套、前端。未部署，真实Paper/Folia调度、文字可见性/遮挡与客户端辨识度仍待实测；Stage 2保持in_progress，下一独立批次测距。

#### 公开临时文字API验证（2026-10-15）

- 文档基线提交`8322e9bb`保留用户确认的后续平台拆分任务、公开能力方向和本批目标签名；运行代码批次仅触及BukkitPlugin。三个公开Java类型、私有DefaultEditorTextService/生命周期listener、composition与服务注册，以及Java消费者示例/说明。不改SimpleSignals、Core/Basic/ATO、存档或版本号。
- 按稳定label ID对完整列表与实际实体比较，未变不更新、只改文字保留实体、位置变化删除重建；每handle一份最新列表，复用PlatformDispatcher/LatestAffinityTaskDispatcher。关闭撤销意图，实际生成/删除及viewer显隐分别在location/entity/player owner；宿主继续处理已停用附属插件的清理。失败完成结果保留在outstanding集合，不把移出活动表当作删除完成。
- 新增10项真实service测试，覆盖列表复制与非法输入/旧序号、身份/同名替换、实际spawn和show暂停时非阻塞关闭、玩家隔离/线程断言、退出/换世界/卸载/owner停用、调度拒绝/实体退休及部分失败清理。注册测试新增服务断言；既有普通模型服务回归未改变。独立复核修正一个非空断言警告，并扩展原并发用例到实际showEntity暂停；首次定向26项有1项因PluginDisableEvent需要Bukkit server绑定失败，改用既有BukkitServiceTestSupport后全部通过，没有放宽生产校验。
- 隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`，`JAVA_HOME=/workspace/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`、`GRADLE_USER_HOME=/workspace/.gradle`。初次产品+Java消费者编译22秒通过；最终定向26项38秒通过。联合执行`:BukkitPlugin:test :BukkitPlugin:shadowJar :SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries --offline --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m' -x :WebUI:installFrontend -x :WebUI:buildFrontend --console=plain`，1分20秒，37task中9执行28up-to-date。
- 联合门禁实际重跑Bukkit 95套398项与SimpleSignals 48套543项，共143套941项，失败/错误/跳过均0；shadowJar与JAR/module边界实际执行，未变Java consumer复用前序成功编译产物。不存在独立lint任务，另有scoped git diff --check通过。不将Core/Basic依赖编译或缓存写成其全套测试重跑。
- 最终Bukkit `src/main`、`src/test`及`src/signalApiConsumer/java`相对路径排序文件清单摘要`eca0afa91105fcf666136c4eb7351e17cb084d1a4f975ed76986577864af0630`；SimpleSignals全部src摘要仍为`be45d174644f72c2e5f1e7ed311b6abccdbeee35734c140ab1af33b96fcc7a57`。工作区与已测候选代码一致。日志`/tmp/javarailways-editor-text-focused-final.log`和`/tmp/javarailways-editor-text-final-check.log`，XML在候选对应模块build/test-results/test。README/契约同步不改变已测代码。
- 未部署、未验证真实Paper/Folia调度或客户端可见性；不声称边界显示或公共Wand/Rail可视化全集已完成。下一批为SimpleSignals边界叠加接线；后续平台拆分任务保持planning。

各批按影响运行SimpleSignals及相邻消费者测试、API/JAR边界检查，收尾执行联合门禁及部署构建；不把离线通过写成游戏验收。Paper/Folia、客户端交互与资源包另列实际验证结果，部署仍须单独授权。测距之前的四批不重开Core/Basic全模块审计或修改ATO算法。

#### 实例参数数值编辑验证（2026-10-15）

- 四个产品文件：SignalWandBackend、SignalWandHandler、WandDialogs及新PlacementWandEditor。设置页明确选已有设备，输入并单独确认，后台只替换placement/revision；不把重新摆放的模型偏好混入实例编辑。复用原manager事务、owner/worker会话及现有摆放/handle保留，无新schema、PDC字段、manager或tick扫描。
- 两个既有suite新增16个执行案例：handler 11项，manager 5个设备kind参数各实际验证rail/ground（共10条保存/重载路径，含两种保护主信号）。验证实际模型/逻辑/映射不变、独立解析XYZ角期望、Euler奇点处未改旋转精确保留、旧identity/anchor拒绝、无变化无写入、取消/过期及真实Basic静态依赖和动态claim阻挡。
- 独立check修正测试构造误用private copy、菜单数量断言、未加载第二种真实signalType及关闭后重用manager；重载使用新manager，不为测试放宽生产关闭语义。产品初轮编译也将表示器的private copy误用改为既有public构造。最终生产复核无剩余问题。
- 同一隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`使用`GRADLE_USER_HOME=/workspace/.gradle`，offline/no-build-cache/no-parallel/max-workers=2、Kotlin in-process且incremental=false，仅排除WebUI前端任务。定向manager测试18秒通过；随后完整`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`32秒通过，34task中3执行31up-to-date。48套543项实际重跑，失败/错误/跳过0；本轮前序已实际编译产品和测试，最终门禁复用未变编译/API产物，并实际执行测试及JAR边界检查。
- 最终全部`SimpleSignals/src`按相对路径排序的文件清单摘要为`be45d174644f72c2e5f1e7ed311b6abccdbeee35734c140ab1af33b96fcc7a57`，工作区与候选一致。最终日志`/tmp/javarailways-placement-final-check.log`，定向日志`/tmp/javarailways-placement-focused-check.log`，XML位于候选`SimpleSignals/build/test-results/test`。未部署、未验证Paper/Folia对话框/客户端，不重跑Core/Basic全套或前端。Stage 2保持in_progress，接下来边界叠加，最后测距；提交预览工具仍不可用。

#### 表示器部分映射后端验证（2026-10-15）

表示器部分映射后端验证：3个产品文件仅放宽覆盖完整性、保留错误键校验，使用既有无进路variant投影缺项，并提供排序不可修改的variant目录。新增1项真实manager综合回归，强化既有目录和跨入口非法引用断言；覆盖新增未配进路、首个替换baseline、区别于default的实际模型场景、已有映射、非法键/variant保持磁盘/source/publication、空映射及重载。独立复核无问题；同一隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`执行`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`，offline、`GRADLE_USER_HOME=/workspace/.gradle`、no-build-cache/no-parallel/max-workers=2，仅排除WebUI前端任务。1分1秒，34task中8执行26up-to-date；未变API consumer复用产物，产品编译、测试及JAR边界实际执行。完整48套518项通过，失败/错误/跳过0；首个候选全部`SimpleSignals/src`文件清单摘要`b6a74bae6469bd4704a31d523930e713e1841354e4cbe91bc31a1274798c1063`。日志`/tmp/javarailways-indicator-partial-check.log`，XML在候选目录。UI正在独立开发，不将未提交UI混入该验证/提交；无renderer、宿主、schema变化，未部署或验证Paper/Folia/客户端。

#### 表示器Wand交互验证（2026-10-15）

- 三个产品文件：既有SignalWandBackend/Handler及新RouteIndicatorWandEditor；编辑器仅保存回调局部不可变映射草稿，复用WandDialogs/原会话及唯一manager事务，不新增玩家草稿管理器或渲染API。新建先捕获真实rail/ground姿态，明确无进路外观、可缺项的映射及最终确认后才完整保存；已有映射/入口编辑使用原model/pose，与Wand偏好独立。明确新建意图始终新建，不把后来出现的已有设备当更新目标。
- 既有表示器binding的ID/revision成对可空，物品writer显式写null/null表示新建，字段缺失或半空拒绝；独立复核发现原STATE_JSON explicitNulls=false会把字段丢失推断为空，改为同一current codec explicitNulls=true并断言实际序列化字段存在。未增加schema代际、别名或兼容reader。
- 新增9项执行案例：handler rail/ground创建2、映射/入口编辑1、取消及失效参数5，manager实际模型/位置保留及跨入口保护1；强化原manager参数化移动/删除用例，验证同一新建意图两次生成不同完整设备，保留原移动/映射/删除断言。实际Basic registry静态依赖和动态claim阻挡编辑，非法variant/跨入口route、取消/旧capture和同配置无写入均有断言；平台调度/物品测试仍为替身，不替代客户端验收。
- 沿用隔离候选、`GRADLE_USER_HOME=/workspace/.gradle`、offline/no-build-cache/no-parallel/max-workers=2；生产编译12秒通过。完整`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`首轮52秒仅严格空字段测试失败，修复后53秒通过（34task中8执行26up-to-date），仅排除WebUI前端任务。完整48套527项实际重跑，失败/错误/跳过0；API consumer复用未变产物，实际JAR边界通过。
- 最终全部`SimpleSignals/src`路径排序文件摘要为`7600395e67db2574cd0936ddc1a3499da905963f643fdcfb4ffffc01a728385e`，live与候选一致。日志`/tmp/javarailways-indicator-editor-final-check.log`，XML在候选目录。独立复核无剩余阻塞；无部署、Paper/Folia/客户端或Core/Basic全测试，不把缓存写成重跑。本轮提交预览工具已不可用，未伪称窗口已打开。Stage 2保持in_progress，下一批为实例参数数值编辑，之后边界叠加，最后测距。

#### 进路删除后端验证（2026-10-15）

- 9个产品文件约50行新增机制：patch合并后按旧新作者route ID差集解除保留设备的固定进路引用、清理表示器映射并checked递增revision；显式upsert仍严格校验。入口设备继续绑定真实边界，空表沿用既有catalog双向禁行；无观察根的信号复用Unavailable/0，表示器在替换baseline刷新无进路外观。未新增tick工作、管理器、资源、Wand交互或schema代际。
- 新增5项执行案例：既有manager集成1项、规范化失败参数化3项（signal/indicator revision溢出、staging失败）、真实Basic planner空表双向阻断1项；其余加强旧断言。覆盖删除A保留B、清空、重载、同ID重建不自动绑定、显式重绑、模型及附属关系不变、未知引用和错误type拒绝、编辑冲突及保存失败原子性。
- 独立复核补充无限制正反可达前置，并把原无请求基线增强为25m/s/已选进路和绿色附属显示，删除后验证0/Unavailable和附属默认外观、空表表示器无进路外观；使用已有准入/净空fixture，不宣称真实列车资源申请或客户端验收。
- 隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`，`GRADLE_USER_HOME=/workspace/.gradle`、offline/no-build-cache/no-parallel/max-workers=2。命令`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`，仅排除WebUI installFrontend/buildFrontend。首次完整门禁1分2秒，34task中8执行26up-to-date；最终增强测试后重跑44秒，5执行29up-to-date。
- 最终SimpleSignals全部48套507项实际重跑，失败/错误/跳过均0；产品/API/JAR检查在前一轮实际通过，最后一轮复用未变产物。最终产品/测试diff相对`7be6ca20`的SHA256为`5b5ad20ba73c1938d8889007785360d9f84a572b1670f235820947c27dcba8d9`，与隔离候选相同。日志`/tmp/javarailways-route-delete-final-check.log`，XML在候选`SimpleSignals/build/test-results/test/`。既有Graal POM/JDK/Gradle警告未扩入本批处理。
- 独立复核无剩余阻塞；未运行Core/Basic全测试、前端构建、Paper/Folia或客户端，未部署。本节只记录后端验证，不证明Wand能力或Stage 2整体完成。

#### Wand删除与重绑交互验证（2026-10-15）

- Route Wand的普通录制手势保留，Shift+右键从所选区域作者表选进路并确认删除；后台复用CurrentRouteRecording和原manager，只upsert保留ID的表。Signal Wand设置页新增设备→有效目标→确认的三页流程，RuntimeSignalWandBackend只改bindingId/revision，包含未绑定、ground和无placement设备。共享WandDialogs只构造原生按钮，不是新的编辑服务或会话框架；无PDC/sidecar字段及版本变化。
- 新增10项执行案例：Route handler 2项，Signal handler 1项正常流程+5项失效参数，真实manager删除/重绑各1项；旧预设断言由列表尾位置改成按动作定位，排序、选中、复制参数断言仍保留。复核修正测试初始化为先合法创建再解除绑定，未放宽既有“必须有关联轨道影响”的候选规则。
- 平台替身验证owner/worker边界、确认前无写入、Esc后重开不锁busy、旧页/重复点击/取消/工具状态/权限/catalog/世界卸载失效。真实backend→manager→store链路验证空表及设备保留、精确修订/目标排序、同绑定无写盘、旧capture/未知目标拒绝、ground/type/提前时间/附属关系不变；真实Basic registry验证静态运营依赖与动态claim阻挡重绑。非真实Folia/客户端操作。
- 同一隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`、`GRADLE_USER_HOME=/workspace/.gradle`，offline/no-build-cache/no-parallel/max-workers=2；生产编译16秒通过。完整命令`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`，仅排除WebUI installFrontend/buildFrontend。首轮51秒暴露旧位置断言，第二轮1分1秒暴露新fixture零影响初始化，修正后最终47秒通过（34task中5执行29up-to-date）。
- 最终全部SimpleSignals 48套517项实际重跑，零失败/错误/跳过。API consumer复用未变产物，JAR边界曾实际执行且通过；未变宿主模块缓存不计为重新测试。包含新WandDialogs的全部`SimpleSignals/src`路径排序后文件SHA256清单的摘要为`32adc798ca899af41545d1d74629d66b1d4735d47f9e409b36af643de8c62e40`，live与候选一致。日志`/tmp/javarailways-wand-route-edit-final-check2.log`，XML位于候选`SimpleSignals/build/test-results/test/`。
- 独立复核无剩余阻塞；未部署、未运行Paper/Folia/客户端、Core/Basic全测试或前端构建，既有Graal POM/JDK/Gradle警告未扩入本批。Stage 2保持in_progress；下一批为表示器新建/映射编辑及相应入口重绑，之后数值实例编辑、边界叠加、最后测距。

### 当前优先批次：停车精度（用户已批准）

- [x] 以真实50ms固定闭塞返库工况复现：最终运营目标20米，实际18.983016737339824米；沿返库方向超调约1.017米。轨迹确认制动开始时仍有约+1.17m/s²加速度，原曲线漏算其jerk消退时间、额外位移与速度。
- [x] 修正Basic现有制动包络与SimpleATO接线；普通mission曲线不把到达容差当几何偏移，Dispatcher使用单倍既有容差并显式保持已接受停车。保留13266c28/fab92929的真实授权余量与越界检查，不改原目标、不扩大容差、不改实际积分或人工驾驶。
- [x] 七项真实往返及受影响模块回归、独立check通过，owner契约已同步；按当前批次提交。未部署、未改schema、未混入其他审计/性能任务，Stage 2仍in_progress。

#### 停车精度验证与证据（2026-10-14）

- 算法与复杂度归[Basic动力学](../../spec/javarailways/basic/train-longitudinal-dynamics.md#从牵引转入制动时的正加速度)，普通目标/通用约束边界归[controller](../../spec/javarailways/simpleato/controller-runtime.md)，完成/保持归[Dispatcher](../../spec/javarailways/simpleato/dispatcher-runtime.md)。原物理轨道授权和资源生命周期不变。
- 真实50ms往返的原固定闭塞场景：100米实停99.8265660573924米，20米返库实停20.124286994944725米；均满足原0.25米容差。默认0.1米用例分别实停99.92069105739239米、20.052128032479487米。120/160米接续、移动授权、101米短接续及真实越界阻断继续通过；完成后另执行5个真实物理帧，车头、零速和claim footprint保持不变。
- 独立check修正了新增消退距离可能掩盖不可制停坡段的门控顺序，并保留回归。普通停留、等待、开门/扩展动作及终到使用已有stopKey保持，下一移动任务成功安装才解除；无新增状态机或扫描器。
- 旧直线资源替身测试反应时间0与50ms离散控制不一致：纯数值独立复现首次停车90.41860833614942米；仅此fixture按一个tick设0.05秒，控制器/执行器共用同profile，目标90米、原0.4米断言和50ms步长不变。不是扩大精度容差或修改生产物理。原“完成后挪动导致这项超调”的推测已撤回；额外完成后静止断言仍保留。
- 最终精确代码树`fe575bdb04fb65ef8e1629daf8d08ea7aaea6a41`；隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`，`GRADLE_USER_HOME=/workspace/.gradle`，offline/no-build-cache/no-parallel/max-workers=2。联合任务`:Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:check :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :Basic:dokkaGeneratePublicationHtml :SimpleATO:dokkaGeneratePublicationHtml`通过；仅排除未改前端的installFrontend/buildFrontend。
- 最后一轮22秒，50task中4执行46up-to-date：完整SimpleATO377项本次重跑；其余复用上一轮同生产树的结果。XML合计Core409、Basic395、SimpleATO377、SimpleATOBukkit8、SimpleSignals502、BukkitPlugin388=2079，失败/错误/跳过均0；上一轮完整受影响模块103秒，仅旧fixture失败，修正后无剩余失败。
- 仅新增3条集中回归（Basic解析响应、ATO四类约束接线、真实默认0.1米用例），其余加强既有断言。所有PARK诊断/临时actuator引用已删除，diff检查通过。Dokka/JDK/Gradle/Graal既有警告未在本批清理；无服务器部署、客户端或Paper/Folia验证，不宣称任意车型/线路都已完成精度验收。

### 当前优先批次：真实停站授权余量与越界完成检查

本节记录用户先批准的授权收尾批次，取代下方“后续独立批次”的暂缓状态；后续停车精度修复与证据见上方当前批次。

用户进一步确认：安全距离作为全局设置，默认5米。静态应用与实际/隔离controller读取同一配置；具体新目标、失败边界和回归归[owner契约](../../spec/javarailways/simpleato/terminal-route-completion.md#当前实施目标全局安全距离与真实完成检查)。预计改动限于SimpleATO路径补全/准入/Dispatcher与测试、SimpleSignals中立终点接管/余量校验、宿主配置及真实/隔离composition；不改Core/Basic物理算法、世界格式或模型包。

- [x] 核对真实物理余量的路径来源、现有安全距离、静态/动态依赖及失败语义，补充owner契约；未仅扩大控制标量、猜未选道岔分支或盲加10米。
- [x] 实现移动闭塞停站实际轨道授权与完成转换检查：使用真实车头位置和Basic committed boundary，越界不能推进后续动作；复用既有路径/授权生命周期，未新增逐tick扫描器或第二个资源管理器。
- [x] 独立check和受影响模块门禁通过，按授权机制及宿主配置拆分提交；未修改制动算法/停车目标/精度容差，未部署或增加schema版本，保留用户其他工作。

#### 本批验证（2026-10-14）

- 最终精确代码树`3d1faaf8721c077f75dff9d8810218376fc85038`，隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`；使用`GRADLE_USER_HOME=/workspace/.gradle`，offline、no-build-cache、no-parallel、max-workers=2。
- 联合任务：`:Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:check :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :SimpleATO:dokkaGeneratePublicationHtml`；成功1分15秒，49项task中15执行/34 up-to-date。仅排除WebUI installFrontend/buildFrontend；本批未改前端。
- XML结果：Core409、Basic394、SimpleATO376、SimpleATOBukkit8、SimpleSignals501、BukkitPlugin388，合计2076，零失败/错误/跳过。Core/Basic本次为up-to-date；其生产代码未改，不能宣称本次重新执行了803项测试。
- 真实Core/Basic/Handle执行的50ms用例覆盖无信号策略停车、100→101米短接续、越过真实边界后禁止换向/归库，并保留原三个固定闭塞回归。站台/普通目的地禁止开门的负向用例执行实际Core位移及publication，但准入/驱动为原fixture替身，不能与真实集成证据混称。
- 新增几何边界与source接管冲突回归；中间站不覆盖最终停车目标，静态/动态终到站偏移保持一致。首次联合回归发现的中间站语义问题已修复；新用例按实际异步准入阶段等待拒绝，不放宽停车条件。
- 独立check未发现剩余生产阻塞；既有Dokka未文档化/链接、Graal POM及JDK/Gradle警告仍有输出。无Paper/Folia、客户端、部署或迁移验证。停车精度缺陷继续保留，Stage 2不标完成或归档。

### 用户纠正后的当前执行顺序

用户确认：固定闭塞应真正取得信号允许通过的完整区间，运营停车目标独立；测试的闭塞本来为40～140米、停车点100米，不是玩家把边界放在停车点。此前仅凭物理授权被截到100米，就将问题归因于制动超调并要求先修动力包络的顺序已被此决定取代；100.322米是否满足停车精度与是否超出完整区间授权必须分开。

- [x] 第一笔：实现[终端物理路径补全契约](../../spec/javarailways/simpleato/terminal-route-completion.md)，复用InspectionSource生命周期，在冻结准入路线时低频补全，并正确复用下一任务与既有授权后缀。Basic保持独立校验，不新增registry，不改持久化。
- [x] 第二笔：SimpleSignals从已编译闭塞路径提供完整剩余路径与区间内部起步请求下界；接入真实50ms返库回归，证明物理claim真正到140米、停车目标仍100米、同一token跨停车换向保留/实际车尾清除后释放。
- [x] 此前暂缓、现已由上方当前批次实现：移动闭塞停站的真实物理授权余量与通用越界完成检查一起处理。试接通用检查暴露的“授权恰好到停车点”语义已用真实余量纠正，未放宽epsilon。

以上源码接缝和限制见[专项研究](research/fixed-block-authority-stop-separation.md)。下方旧诊断与验收保留历史证据，不再作为未经用户确认的修复优先级。

### 完整闭塞联合门禁（2026-10-14，授权验收通过，停车精度另记）

- 中立补全、后缀复用、同前缀授权重绑、原路径→补全路径的编辑保护、相接片段声明匹配及控制器/调度器物理投影已落盘，独立check未发现新的生产阻塞。不是停车精度修复，也没有放宽Basic准入校验。
- 精确源码树`fbf2b57342c451eebbc32fee39fb345319f02e5d`导出到`/tmp/javarailways-fixed-block-authority.nsoyCU`，使用`GRADLE_USER_HOME=/workspace/.gradle`执行`:Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:check :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :SimpleATO:dokkaGeneratePublicationHtml --continue --offline --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m' -x :WebUI:installFrontend -x :WebUI:buildFrontend --console=plain`。1分31秒，49项task中18项执行、31项up-to-date。
- XML汇总：Core409、Basic394（本次两者up-to-date，复用同候选先前结果）、SimpleATO363、SimpleATOBukkit8、SimpleSignals497、BukkitPlugin384；共2055项，2054通过、1失败、错误/跳过0。Java consumer、实际JAR边界、Dokka通过；既有文档未注释/链接、Graal POM及JDK/Gradle警告仍存在。前端任务排除，未部署/未验Paper或客户端。
- 唯一失败：真实50ms“100米停站后返20米库位”的最终位置断言（新测试自设±0.6米）读到18.983016737339824米；运行已完成且位于真实0～40米库前闭塞授权内。另两例100→120、100→160后返库完整通过。是否把该精度断言留给下一独立批次，已向用户明确询问；未偷偷增大容差、修改停车点/制动或以此宣布整体通过。
- 测试场景修正均为作者输入：库前新增真实0～40米闭塞；库位坐标仍20米，入口/返库point ID与canonical Destination ID一致；显式`RouteAllocationTiming(0,0,2)`用于证明先使用旧授权再续申请，第一次停车请求计数只捕获一次。没有生产存车规则改动。
- 最终源码树`b8e547540bc3409f02ec83c081c136707a8090fb`只修正本批新增、没有契约依据的±0.6米测试验收：改验最终真实Mission几何目标严格保持20米、速度0、完整实际车体处于正式0～40米闭塞、真实token/轨道claim覆盖车头车尾，以及原归库台账；没有增大容差或修改制动。独立check复核这些断言未发现伪造目标/授权。按同一命令重跑40秒、49项task中5项执行44项up-to-date；全部2055项结果失败/错误/跳过0，其中SimpleSignals497项本次完整重跑，其余未变模块复用前一联合候选结果。Java consumer、JAR边界及Dokka均通过。
- **历史未修复记录（后续进度见本页顶部）：**第一种返库工况目标20米、实停18.983016737339824米，误差约1.017米。该回归只证明完整固定闭塞授权与存车流程，不证明停车精度；下一批应采样真实制动轨迹再诊断，不能因本次授权门禁绿色关闭该问题。移动闭塞真实停车余量/通用越界完成检查亦保持未完成。

用户批准继续Stage2收尾。已切回本父任务；全项目代码审查仍作为独立子任务保留，不擅自重开Core或切入Basic余项，也不把完整旧服迁移自动加成新runtime开发前置。下方2026-10-09状态与历史未勾选项须按后续真实实现解释。

- Core本轮源码审查及已确认缺陷处置已由`24cf744f`、`416c70ef`收尾；完整独立候选2069项通过（具体命令/限制只在[Core记录](../10-09-stage2-code-review/implement.md#core缺陷收尾2026-10-14帧间六批完成之后)维护）。该证据不替代Stage2缺失交互或现场验收。
- 保护区域到连续移动区段的出口空间已由既有中立entry-clearance加当前车长/安全余量实现，不再按下方旧待办重复增加边界策略。flank/approach条目是具体联锁规则需要时才做，不造占位资源。
- [x] 固定闭塞返库授权回归：真实50ms“前进—区间内停车—停车换向—同一双向闭塞返库”及两种后续任务接续已通过，详见本页顶部最终门禁。原claim被截到运营停车点导致的失败已用完整物理授权纠正，Basic覆盖校验保持不变；旧失败数据保留于[历史诊断](research/stage2-runtime-closeout-2026-10-14.md#7-physical-return-regression-exposes-stop-overshoot)。本项不是停车精度或通用越界完成检查验收。
- [x] 先独立交付Signal Wand命名rail摆放预设选择，复用现有immutable catalog、PDC revision、`applyPreset`和manager patch；仅改变未来明确rail放置/更新，不自动改旧设备，ground保持原样。先写契约再实现，现见[工具契约](../../spec/simple-signals/backend/section-authoring-wands.md)，不冒称全部样式编辑完成。返库测试发现独立缺陷后调整提交顺序，避免将已知失败回归混进本笔功能。
- 测距分岔选路已于2026-10-14由用户确认：自动选择物理最短路，不受当前道岔选择限制，不实际扳动道岔；本轮先完成上述两笔，再按此决定细化测距批次。其余边界地面模型/完整叠加、进路删除引用策略、新进路表示器映射作者交互仍须具体合同。热重载不因旧批次排除文字自动升级为本阶段必需范围；不临时制造model variant或预设默认值。

本次保持批次独立验证与提交，不部署/修改远端、不增加runtime兼容或schema版本，不覆盖用户模型、TODO及docs-site脏状态。现状核对见[运行收尾研究](research/stage2-runtime-closeout-2026-10-14.md)和[工具收尾研究](research/stage2-authoring-closeout-2026-10-14.md)。

### 命名轨道预设离线验收（2026-10-14）

- 实现仅涉及SimpleSignals六个源码/测试文件：catalog提供按名排序、不可修改的模型预设参数；设置页复制偏好到现有物品PDC；明确rail放置复用`applyPreset`和已有manager事务。倾斜值在选择时确定，不引入动态继承、global settings、tick枚举或新渲染器。
- Handler覆盖选择/清除、严格PDC、过期回调、源预设变化/删除、有效模型改变及ground旁路；真实manager覆盖主信号和既有表示器的预设参数保存、逻辑绑定、其他设备与JS输出保留。独立check将原有两条ground成功用例参数化，避免被新增rail断言挤掉。
- 精确已暂存源码候选`/tmp/javarailways-stage2-presets.FnYi5c`不含未完成的返库测试。使用`GRADLE_USER_HOME=/workspace/.gradle`执行`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries --offline --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m' -x :WebUI:installFrontend -x :WebUI:buildFrontend --console=plain`，2分03秒、34项task实际执行；47套/493项测试，失败/错误/跳过均0，Java consumer和实际JAR边界检查通过。
- 此门禁排除了WebUI前端安装/构建，不代表新前端产物或其他模块测试重新通过；存在既有Kotlin/Graal POM、JDK/Gradle警告。未部署或验证Paper/Folia客户端。包含返库WIP的当前工作区此前完整执行494项时493通过、1失败，后续聚焦返库重跑仍因已记录超调失败；不能用本候选绿色结果声称当前全部工作区或Stage2已通过。

## 历史状态汇总（2026-10-09，基线 6cf5805d）

本节保留当时的盘点；当前进度以顶部收尾批次及后续验证为准。旧批次中的“待实现”或默认进路等描述，
不能越过后续修正被当作当前合同。文档规则见[文档职责](../../spec/javarailways/shared/documentation-ownership.md)。

- **已有实现和离线回归**：SimpleATO领域迁出、SimpleATOBukkit源码/嵌入边界、SimpleSignals独立打包；
  保护区域/固定闭塞/进路、JS信号包与树形观察、信号模型显示、区间/进路/主信号/附属设备Wand。
  “领域迁出”不代表所有运营Bukkit编辑适配器均已迁移，后者仍由审查任务跟踪。
- **拓扑编辑后的局部重验已经实现**：生产订阅在`SimpleSignalsWorldRuntime.kt`接入拓扑变化，先失效相关检查，
  后台编译并恢复有效定义；`SectionTopologyRevalidationTest`覆盖相关区间禁行、修复和无关区间保留。
  机制见[局部重验](../../spec/simple-signals/backend/section-topology-revalidation.md)，不再列为待开发功能。
- **编辑和显示仍有缺口**：边界地面标记/完整叠加、测距、摆放预设与实例编辑界面、进路删除和新建进路显示器的完整外观映射界面。
  旧“guided Wand”总项包含多个交互，不能代表所有工具都没实现；模型显示的离线实现与现场验收分开记录。
- **仍需专项核实**：连续territory出口精确授权策略、完整返库任务与反向流程覆盖、生命周期/存档综合验收。
  后文未勾选的综合项不直接证明某个子能力没有实现，须按生产调用和测试核实后处置。
- **审查进行中**：[Stage 2代码审查](../10-09-stage2-code-review/prd.md)跟踪旧路径、metadata、API与适配器归属、文档及复杂度；
  `6cf5805d`仅完成无调用Java原型清理，不代表模块语义审查完成。
- **现场未验证**：真实Paper/Folia加载/调度、玩家对话框和工具交互、客户端模型/资源包、现场卸载恢复与性能。
  未运行服务器或客户端；本汇总不宣布Stage 2完成，不用临时smoke实现替代正式版。

## 2026-10-09 Core ThreadGuard API follow-up

- [x] Promote ordinary thread identity checks to the minimal `core.api.threading` consumer surface: `ThreadGuard` exposes
  only `is*`, diagnostic `currentContext` and fail-fast `ensure*`; `ThreadContext` is diagnostic. Core, Basic, SimpleATO,
  SimpleSignals and Bukkit guard consumers use this path, and the unpublished former `core.util` guard/context/bridge entry
  points are removed without aliases. The consumer guard cannot register, clear or enter an owner context.
- [x] Split trusted host integration into `core.api.threading.host`. The first live platform bridge registration wins and
  a duplicate fails; BukkitPlugin retains the returned identity token and closes it at the original shutdown position.
  Token close is idempotent and can remove only its exact registration, so an old token cannot clear a later bridge.
  Bridge lookup remains non-blocking, process-visible and non-scheduling.
- [x] Keep async-tick entry private to Core. SimpleATO headless timetable simulation deliberately retains the scoped
  `core.util.IsolatedSimulationThreadMarker` first-party implementation seam for its exclusively owned world; this is not a
  third-party API and was not repackaged as a capability-free public helper. Core/SimpleSignals deterministic tests may
  exercise the same seam. Eliminating this remaining util leakage requires a separate capability design.
- [x] Extend the existing artifact boundary gate with exact ThreadGuard checks: the Core JAR must expose the consumer and
  trusted-host API classes and retain none of the former entry points; the consumer guard class must not expose install,
  clear or isolated-context members; SimpleSignals bytecode must not reference the former guard path.
  No broad domain inventory or package-wide string ban was added. No runtime policy, schema, version or compatibility path
  changes in this batch.
- [x] Validation passed with `/workspace/.gradle`: Core 46 suites / 212 tests, Basic 63 / 389, SimpleATO 46 / 351,
  SimpleSignals 47 / 485 and BukkitPlugin 85 / 353, with zero failures/errors (existing Core/Basic
  skips remain 6/1). Core Dokka generation, Java signal-API consumer compilation, SimpleSignals plugin JAR verification,
  the exact artifact boundary gate and `git diff --check` passed. Existing Dokka coverage/JDK warnings and two one-off
  physics-overload diagnostics are not live deployment evidence. No Paper/Folia runtime behavior changed or was tested.

## 2026-10-09 module-boundary and packaging gate

- [x] Add one root `verifySignalModuleBoundaries` direct-declaration/artifact regression guard. It rejects the current
  Basic/SimpleATO/adapter/host reverse project edges, and ensures any SimpleSignals host project dependency is not declared
  in `api`, `implementation`, `compileOnlyApi` or `runtimeOnly`; even `compileOnly` cannot point at SimpleATOBukkit. It
  intentionally does not encode exact domain-class inventories or force
  SimpleSignals to depend forever on every current host module.
- [x] Reuse `SimpleSignals:verifyPluginJar` for its independent descriptor and no-shadow assertion, then additionally scan
  SimpleSignals bytecode for a forbidden SimpleATOBukkit forwarding dependency. Inspect the adapter and host artifacts to
  prove SimpleATOBukkit has no descriptor, `JavaPlugin`, `ServicesManager` or SimpleSignals reference; the JavaRailways
  shadow JAR must embed `SimpleATOBukkitModule.class`, retain the sole host descriptor and exclude SimpleSignals classes.
- [x] Put the gate in the repository CI build and document the same command in the shared/module owner specs and
  SimpleSignals README. This batch changes no runtime behavior, schema, version or compatibility path. A focused local
  `:verifySignalModuleBoundaries` run passed against the actual JARs. The affected module gate was then rerun from source:
  SimpleSignals 47 suites / 485 tests and SimpleATOBukkit 2 suites / 2 tests passed with zero failures, errors or skips;
  plugin JAR verification also executed. Existing Gradle/JDK/native warnings and one physics-overload diagnostic are not
  deployment or performance evidence. Live Paper/Folia loading and client rendering remain separate validation.

## 2026-10-09 Signal Wand ground-placement follow-up

- [x] Extend the existing two-step Signal Wand placement without changing logical binding: right-click air keeps the
  rail-targeted `RailAttached` path, while right-clicking a real block face captures its centre and the player's horizontal
  facing on the owner thread and passes one immutable upright `Ground(ModelPose)` to the worker.
- [x] Generalize the internal Wand backend placement input to the already-supported `ModelPlacement` union. Fixed-block,
  protection current-entry/fixed-route primaries, attached signals and existing route indicators reuse their exact existing
  catalog/runtime/publication/topology guards, edit permit, manager patch, persistence and retained model lifecycle.
- [x] Ground placement performs no rail lookup and changes display only; the left-click section/entry/primary binding remains
  authoritative. No schema/version/compatibility path, renderer, placeholder rule, offset/style UI or route-indicator mapping
  authoring was added.
- [ ] Live Paper/Folia block-face interaction and client rendering remain deployment verification; offline gate evidence is
  bounded to deterministic handler/runtime-manager tests for now. The final offline gate passed 47 suites / 485 tests with
  zero failures/errors/skips, `:BukkitPlugin:compileSignalApiConsumerJava`, plugin JAR verification and scoped
  `git diff --check`; independent review found no remaining production finding and added the event-level block/Shift-right
  regression before the final gate.

## 2026-10-08 Signal Wand presentation-device follow-up

- [x] Add separate model-only catalog selection to the strict Wand item state, settings and status bar. The selected
  presentation model is independent from `signalType`; capture and both continuations retain exact signal/model catalog,
  runtime, publication, topology and item revisions.
- [x] Add attached-signal control: left-click resolves placed rail-attached fixed-block/protection primaries by rail
  position, direction and 0.75 m tolerance, then lists a new attachment and every exact existing attachment. Right-click
  creates or updates one `DeviceModelDefinition` through the normal manager patch; deletion removes only the exact attachment.
  Generated identity and four-device collision checks, primary/attachment revisions, persistence, model validation and
  edit permission remain in the existing transaction. It creates no rule, inspection or resource.
- [x] Add directed-entry selection for exact existing route indicators. Right-click preserves the complete no-route and
  route-to-variant mapping while changing model/rail placement; deletion removes only that indicator. Definitions remain
  selectable even when their route is unavailable.
- [ ] Route-indicator creation remains blocked intentionally: current Wand/package data has no reviewed source for
  `noRouteModelVariant` plus every authored route's variant. Do not manufacture one variant for all routes, use `default`,
  or infer names from route IDs. Ground placement, measurement, presets/styles, hot reload and live client/Folia remain out.
- [x] Offline gate passed: `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` ran 483 tests with zero
  failures/errors/skips; plugin JAR verification and scoped `git diff --check` passed. One existing physics-overload test
  diagnostic and existing JDK/Gradle warnings are not live performance evidence. No world schema, compatibility reader,
  resource/inspection count or production deployment changed.

## 2026-10-08 Signal Wand protection-primary follow-up

- [x] Add a control-kind setting independent of placement/deletion mode and signal type. The current unpublished Wand PDC
  now uses a strict tagged binding for fixed-block, protection current-entry and protection fixed-route primaries; protection
  update/delete bindings retain exact signal ID and revision, while creation is explicit. No old Wand-state reader or schema bump.
- [x] Resolve directed protection entrances through the published rail reverse index, same-rail canonical position tolerance
  and inward facing. A SimpleSignals-owned continuation chooser lists explicit new choices and every exact existing device;
  fixed-route creation lists only currently usable authored routes, while retained unavailable devices remain updateable/deletable.
- [x] Reuse the one manager patch for protection creation/update/deletion. Creation uses a collision-checked shared device ID,
  revision 0 and intentional 0-second extra lead; updates preserve ID, logical binding and lead time; deletion removes only the
  selected primary and its direct attachments. Other primaries sharing the entry/route remain.
- [ ] Live Folia dialog/item interaction and client rendering remain deployment verification. Attached-signal placement,
  route indicators, ground placement, measurement, styles/presets and hot reload remain outside this follow-up.
- [x] Offline gate passed: `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` with 47 suites / 481 tests,
  zero failures/errors/skips; plugin JAR isolation and Java consumer compilation passed. Focused tests cover the pure indexed
  entrance resolver, strict tagged PDC, owner-thread chooser staleness, both protection kinds, multiple devices per binding,
  unavailable authored-route retention, shared identity collision behavior through the reused generator, exact attachment
  cascade, manager persistence/publication/model effects and unchanged fixed-block behavior. Independent review made the
  boundary-ID publication index reject duplicate identities instead of silently collapsing them, and added an exact stale
  signal-revision regression. `git diff --check` passed.

## 2026-10-08 Signal Wand two-step authoring follow-up

- [x] Replace the temporary one-click edit interaction with item-bound placement/deletion modes. Placement left-click
  resolves and stores the exact world/block/entry-direction binding with a checked state revision; right-click
  revalidates that binding and submits identity/upright rail-attached placement. The action bar and lore expose mode,
  type and binding, and a bound item receives enchantment glint. Switching modes clears the old binding.
- [x] Add fixed-block primary deletion through the existing manager transaction. The patch removes the selected primary
  and every directly attached device atomically while retaining the block, other entrances and unrelated devices.
  Catalog/runtime/publication/topology and exact item-state guards remain in both asynchronous stages.
- [x] Create a primary at an empty bound entrance with a generated namespaced UUID device ID, revision 0 and intentional
  `inspectionLeadTimeSeconds = 0.0`. The user confirmed that zero means no plugin-added preparation lead; SimpleATO still
  takes its dynamic lookahead/braking horizon and the block authority floor remains. This is a current Wand policy, not
  a placeholder or package field. Generated IDs are checked against all existing device identities before the manager
  patch so a collision rejects without rebinding or overwriting. Existing updates preserve their prior lead time.
- [ ] Live Folia interaction, actual client glint/model rendering and plugin/world unload behavior remain deployment
  verification; protection signals, separately placed attached signals, measurement, ground placement, styles/presets
  and hot reload remain outside this follow-up.
- [x] Offline gate passed: `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` with 46 suites / 472
  SimpleSignals tests, zero failures/errors/skips; plugin JAR isolation and Java consumer compilation passed.
  Focused tests distinguish deterministic owner/worker interaction from real manager persistence/publication effects;
  creation verifies zero extra lead, generated identity, JS/model binding, collision, edit denial and stale topology;
  no live Folia/client assertion is claimed. Scoped `git diff --check` passed.

## 2026-10-08 Signal Wand fixed-block vertical slice

- [x] Expose an internal immutable player-facing catalog view sorted by `signalType`; it contains only stable type
  identity and display name and retains the exact catalog identity for stale-capture checks. The filesystem source,
  modelId and logicId do not enter the world authoring contract.
- [x] The initially implemented command-selected type UX was rejected and superseded in-place. The only command is
  `/simplesignals wand signal`; a ready non-empty catalog supplies the first sorted type as the deterministic default.
  A stable PDC marker is separate from JSON item state `(random itemId, non-negative stateRevision, signalType)`.
  Shift-right-click opens the SimpleSignals-owned native settings dialog, while a one-per-holder entity-scheduler task
  refreshes the mode/type action bar every 10 ticks. Settings and edit continuations re-read the complete item state,
  so changing away and back still invalidates old work. Corrupt state, stale dialog, removed type, revision overflow,
  lost permission and plugin/player retirement do not write. No host-private Wand/Dialog implementation is imported.
- [x] Add a pure fixed-block entrance resolver using the existing rail index and 0.75 m tolerance. It resolves
  FORWARD/REVERSE from the look direction and compiled entrance, rejects one-way reverse entry, shared-boundary
  ambiguity, block-body clicks and entrances without an existing primary signal.
- [x] The first slice only updates an existing primary: checked device revision +1, selected `signalType`, and
  identity/upright rail-attached placement are submitted in one normal manager patch. Existing inspection lead time,
  signal identity and controlled entry remain unchanged. The manager revalidates the type and atomically prepares
  persistence, JS, model binding, edit permit and publication; denial/staleness retains the old complete state.
- [ ] Live Folia item interaction and client model rendering remain unverified. Creating/deleting primaries, choosing
  inspection lead time, presets/styles, ground placement, protection/attached signals and hot reload remain outside
  this slice. No schema/version or compatibility path changed.
- [x] Independent review of the corrected settings/item-state/status amendment kept the captured catalog/publication/topology check inside the manager transaction guard,
  hid the full package catalog behind the minimal immutable authoring view, validated PDC intent at construction, and
  made the real-manager test prove that changing `signalType` changes both the JS output and selected model. Review
  additionally closed status-task restart races during plugin shutdown, made delayed retired callbacks harmless,
  invalidated inventory-drag edits, and required exact item state before starting an edit.
- [x] Corrected focused handler tests and the full offline gate executed 46 suites / 464 tests
  with zero failures, errors or skips; `:SimpleSignals:check`, `:SimpleSignals:verifyPluginJar`,
  `:BukkitPlugin:compileSignalApiConsumerJava` and `git diff --check` pass. Gradle emitted existing native/deprecation
  warnings and one physics-overload test log; these are not live Folia or performance evidence.

## 2026-10-08 JS-only signal type 修正（取代下方 provider 拆分）

- [x] 删除 world/domain 的 `BuiltIn | Package` provider union 与 Kotlin 规则表执行路径。固定闭塞和保护主信号只保存
  Kotlin `signalType`／JSON `type`，从 SimpleSignals catalog 解析同一个 modelId + logicId。
- [x] 未发布 world schema 保持 version 1，严格拒绝旧 `provider`、`packageSignalId`、规则表和显式主信号 model；
  不加 alias、reader 或 upcaster。保留每设备 `inspectionLeadTimeSeconds` 与可选实例 placement。
- [x] 以下 2026-10-08 provider-union 条目仅是被本节取代的实现历史，不是 CURRENT 合同。

## 2026-10-08 已取代的 provider 拆分历史

- [x] 曾实现的 Kotlin built-in／filesystem package provider union 已在未发布分支内撤销；它不构成 current schema、
  runtime 或扩展合同。当前只有 catalog signal type + JS logic，历史提交中的 provider 字段与测试数量仅供审计。

## 2026-10-08 统一 catalog signal type 的生产模型绑定（后续 JS-only 修正后的语义）

- [x] 固定闭塞／保护主信号由同一 SimpleSignals catalog signal type 同时选择 model 与 logic；
  世界 current schema 1 只另存 `type` 与可选 `placement`，禁止保存可冲突的显式 modelId 或 Kotlin rule。
  附属信号与进路表示器仍是 presentation-only 显式模型。
- [x] 复用 catalog 已 materialize 的模型、`PreparedSignalModels`、handle retention 和显示发布锁；catalog pending／
  缺失／未知时不创建错误 handle，catalog 成功后在 exact runtime/publication/topology 守卫下替换显示绑定。
  定义候选以同一个捕获的 catalog 同时准备 epoch runtime 与模型；失败保留旧磁盘、publication、sources 和 handles。
- [x] 直接修正未发布 sidecar schema 1：两类主信号严格显式写出 `type`，只另存可选 `placement`；modelId 与
  logicId 由同一个 catalog signal type 提供。不升版，不增加兼容 reader 或 upcaster；固定 fixture 覆盖严格字段校验。
- [x] 聚焦回归覆盖 JS 与模型同源选择、运行期 variant 对 catalog model 校验、catalog pending／未知不创建错误模型、
  非法改绑原子保留旧状态。独立检查补充 exact materialized model 身份、catalog 在候选准备与提交间就绪时拒绝
  过期候选，以及 topology 重验后按新 root 重算轨道附着姿态；仍复用既有 handle、worker 和 publication 锁。
  离线完整 `:SimpleSignals:check` 为47套/504项，零失败／错误／跳过；plugin JAR 校验和 Java consumer 编译通过；
  真实 Folia、客户端画面、Wand、热重载与生产规模性能仍未验证。

## 2026-10-07 统一信号包一等类型格式纠正

- [x] 直接修正未发布 config-version 1：`pack.yml` 只保存包元数据；`model/`、成对同 stem 的
  `logic/*.yml`／`logic/*.js` 和 `signal/*.yml` 按安全相对路径派生稳定 namespaced modelId、logicId、signalType。
- [x] signal type 文件取代旧 combination 概念，保存玩家显示名并引用一个 modelId 与一个 logicId；
  跨包引用继续两阶段、与目录顺序无关，旧 foundation 布局没有兼容 reader/upcaster。
- [x] catalog/loader/runtime 测试改用一等 signal 文件。
- [x] BukkitPlugin 提供 worker-safe 的公共普通模型 YAML compiler；旧 model-only loader 与统一信号包共用其
  parts/inline/import、场景、variant 和 placement 编译。统一包同包 model import 只导入 identity-grouped parts，
  不继承目标 default/variants/placement；循环、缺失引用、非法 variant、不可变结果与捕获后不重读均有聚焦测试。
  Car/Rail 专用包未迁移；该历史提交尚未包含生产启动、世界绑定和 signal epoch，后三者已由后续批次完成。
- [x] signal epoch 内存绑定已显式区分 catalog `signalType` 与已放置逻辑 `deviceId`；observation publication/plan
  也使用 device 语义命名，不按同名、模型或路径猜绑定。后续批次已在保持世界 schema 1 的前提下加入严格
  JSON `type`／Kotlin `signalType` 持久化和启动 catalog，见本节末项。

## 2026-10-07 JS 包与树形前视决策收敛（本提交仅文档）

- [x] 历史文档批次曾将目标包收敛为 `pack.yml`、`model/`、`logic/` 及清单组合；其布局已由上方
  一等 signal 文件格式取代，稳定 namespaced 跨包引用要求保持不变。
- [x] 确认复用 GraalJS 技术栈而不导入 `BukkitPlugin` 私有 runtime；实际规则在物理快照之后的同步信号阶段执行，
  准备工作才可异步，不规划 context pool、watchdog 或异步求值结果交接。
- [x] 将自动前视目标改为完整分支树，扫描距离逐分支累计；删除节点、分支、时间、内存和整波预算要求，
  明确包作者负责脚本性能与终止。
- [x] 将遍历兜底收敛为逐分支遇环停止并向 JS 报告终止原因；兄弟分支继续，不使用 DAG 全局拒绝、SCC、
  fixed-point 或陈旧 permissive 输出。
- [x] 历史批次建立包发现／跨包解析与 GraalJS runtime 基础时曾保持 Java/Kotlin provider 可用；严格 current schema 1、
  两阶段稳定 ID 解析、捕获 JS 源、fresh realm 沙箱、exact-once setter 和 detached async 取消均由聚焦测试覆盖，
  未在同一提交接入树形 observation、世界或插件启动。
- [x] 编译不可变树形 observation plan：从已准备的有向区间连接图逐路径展开，状态身份包含区间、方向、入口和保护进路上下文；
  逐分支累计区间/连接距离，连续短区间扫描到距离边界，分叉保留全部候选，汇合保留独立 occurrence。
  道岔 continuation 与保护进路作为运行期筛选元数据保留；逐分支哈希索引发现回边，仅生成该分支的 `cycle` 叶，
  循环段不进入下游信号依赖集合，兄弟分支继续。编译结果另汇总区间、非循环信号、道岔和保护进路依赖，
  不读取物理 epoch、执行 JS、选择进路或修改世界。聚焦 `SignalObservationPlanTest` 10项通过。
- [x] 从真实 world section/topology publication 构造连接图：复用 exact `TopologyState`、固定闭塞快照、保护区域编译快照、
  保护进路选择索引和既有 traversal/continuation，不建立第二套物理图。准备时计算普通轨道间距、所有合法道岔分支、
  保护进路 choice、track-end/route-unresolved/topology-unavailable 及有向环；同入口多逻辑信号和附属显示 root 保持共享。
  图作为不可变值随完整 runtime publication 安装，普通定义准备与局部拓扑重验失败均保留旧 publication。
  正长度无区间环只生成命中分支的 `cycle` 叶并保留兄弟分支，零长度环拒绝；未受影响保护进路可在外层 exact-topology
  index 下跨无关 revision 复用。独立复核修正旧 revision 误拒绝和正长度环整图拒绝；完整 SimpleSignals 486 项测试、
  Java consumer、插件 JAR 和 diff check 通过。本批不读取 epoch、不执行 JS、不发布规则结果，不改 schema/version。
- [x] 将显式内存绑定接入既有同步 signal epoch：同帧占用／道岔／保护进路事实投影为树形 JSON，按非循环下游依赖
  顺序每设备至多求值一次；完整 setter 结果及 model variant 校验后原子替换快照，异常／未知 variant／不可用物理状态收紧为0，
  不保留上一 epoch 的许可。附属显示继续读取主设备同一结果。当时曾存在 Kotlin/JS 混合调度；上方 JS-only 修正已经
  删除全部持久化 Kotlin 规则、混合 provider 与重复 Kotlin 基线，只保留 JS 设备之间的同 epoch 有向顺序。
  离线功能门禁中的 Graal 调用不是吞吐／延迟基准；fresh Context和树投影的生产规模成本
  仍待代表性包基准及在线观测，不能据单元测试通过宣称 tick 性能已验收。
- [x] 把 catalog signal 类型引用加入尚未发布的世界 current model并接入插件启动 catalog：该批曾使用可空
  `packageSignalId` 和 provider union，现已由上方 JS-only current schema 1 直接修正为必填 `signalType`／JSON `type`；
  原 `signalId` 继续作为世界 `deviceId`，不从设备、模型或
  文件路径猜测绑定。插件在启动 worker 一次加载 `signal-packs/`，manager 共享 catalog/runtime，并为每个 world 编译
  `deviceId -> signalType` 的 `SignalPackEpochRuntime`。catalog pending、缺失或启动／拓扑重验失败时绑定设备保持
  `Unavailable`／0；catalog ready 后定义编辑严格编译，未知类型拒绝且不取得编辑许可。异步 attach 以 exact world
  runtime/publication/topology 守卫并通过原子 source replacement 安装。没有 upcaster、兼容 reader、热重载、Wand
  选择或模型 materialize；schema version 保持1。聚焦验证4套/123项通过。最终离线
  `:SimpleSignals:check :SimpleSignals:verifyPluginJar :BukkitPlugin:compileSignalApiConsumerJava` 成功：SimpleSignals
  47套/497项全部实际执行，零失败/错误/跳过，插件 JAR 校验实际执行；未变的 Java consumer 编译复用缓存。
  `git diff --check` 通过。构建中的 JVM deprecated/restricted 警告及一次 physics overload 日志不作为性能证明；
  未做 live Folia、客户端或生产规模延迟验证。

本节前四项属于 2026-10-07 的文档收敛提交；随后独立 foundation 提交实现包 catalog、Graal 依赖和 JS runtime，
仍不修改持久化版本或兼容路径，也不声称树形扫描、world/epoch 接线或游戏内放置已经可用。
精确契约由 [JS 规则 owner 文档](../../spec/simple-signals/backend/js-signal-rules.md)维护。

## 2026-10-06 JS 信号规则契约批次（仅文档）

- [x] 记录用户选择 JS 处理距离敏感逻辑，最远扫描距离只控制输入，不新增 Kotlin 距离条件表。
- [x] 根据真实规则／区间边界编写 [TARGET 契约](../../spec/simple-signals/backend/js-signal-rules.md)：工作方式、只读输入、setter、失效、内部机制、复杂度、伪代码与测试断言。
- [x] 完成 [源码对照](research/js-rule-runtime-source-map.md)与独立文档审查；修正“输入相同必不重算”的过度承诺，保留现有事件唤醒和输出不变停止传播语义，不新增输入缓存。
- [x] 后续评审已于 2026-10-07 决定包目录、同步执行阶段、完整树形分支、无通用预算及遇环停止；
  精确 loader/API 字段仍由 owner 契约和独立实现批次确定。

本批不修改运行代码、依赖、存档版本、Wand 或循环策略；已有显式下游距离输入不等于已实现最远距离自动扫描。
验证仅为源码／文档审查、`git diff --check` 与 curated context 校验（implement/check 各12项通过）；
既有大型 index/model 文档仍有注入截断警告，专题 JS 草案单独列入 manifest。未执行构建、JS 引擎测试或 Folia 验证。
本批单独提交后停下供用户检查，Stage 2 不归档。

## 2026-10-06 未发布格式版本纠正

- [x] 按用户要求把 SimpleSignals world-definition schema 从误升的3恢复到1，保留默认进路删除和两端独立规则。
  只改本插件版本常量、current fixture、版本拒绝回归与对应文档；不改其他模块版本或增加2/3兼容分支。
  先前批次日志中的2/3仅为当时实际执行的历史记录，不代表发布/冻结基线，不能作为后续升版依据。
  门槛见[版本号与发布基线](../../spec/javarailways/shared/versioning.md)。生产仅修改codec版本常量；6个测试文件、4份固定JSON同步schema 1。
  方向fixture拒绝0/2/3/99；缺方向仍为FORWARD，双端/model/attached内容保持；schema 1包含已删除defaultRouteIds仍严格拒绝并保留源文件。
  离线`:SimpleSignals:test :SimpleSignals:verifyPluginJar`实际通过：43套/450项，零失败、错误或跳过，42秒。
  生产与测试Kotlin编译、测试资源处理、JAR、插件隔离和测试本轮实际执行；其余依赖复用缓存，本次未重跑Java API consumer。
  独立checker确认四份fixture除版本3→1外全部值不变、跨模块代码diff为空、21个文档链接与10+10上下文引用有效、managed/state块不变，`git diff --check`通过。
  日志`/tmp/javarailways-schema-one-check.log`；含既有构建警告和一次physics overload，不作为性能证明。未部署、未迁移服务器文件；用户已批准提交并继续核对余项，Stage 2仍进行中。

## 2026-10-05 信号对照修正（本轮优先）

- [x] 删除默认进路，并要求固定进路/当前入口信号都具有匹配的有效授权请求。已独立检查，本批提交后停下供用户审阅。
  - 6个生产文件删除默认配置、索引、专用footprint与冲突计算；没有匹配请求或请求其他进路时固定信号也停车。
  - 当时9个测试文件及3份fixture同步sidecar schema 2（误升，现由上方版本纠正批次取代）；不改宿主存档、Basic/ATO/Core API、实际资源仲裁或模型包版本。
  - 聚焦10套/148项实际执行通过。独立完整离线门禁`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`通过：41套/429项，零失败、错误或跳过，39秒。
    实际执行测试编译、完整测试、`verifyPluginJar`及Java consumer编译；生产编译/JAR复用先前实现构建结果。
    `git diff --check`通过，未配置独立lint任务；日志`/tmp/javarailways-request-only-check.log`。
    测试组合真实运行类与构造观测/准入帧，不等于live Folia或客户端验证；不声称出口净空已实现。
- [x] 实现基于列车长度与前方停车位置的保护区域出口净空，不以取得后继闭塞代替净空证明。
  - 2026-10-06 完成独立检查：区域source声明中立入口/出口；SimpleATO以同帧车身保守跨度和原有安全距离扩长请求floor、单次前视，
    使用既有授权/停车/站台/前车/信号约束证明净空并立即安装入口等待。共享信号经已有串行epoch接收变化，必须同时满足匹配请求、授权、设备和净空；只精确排除本次入口自身红灯，不删除最终保护约束。
  - Core公开已有原子Train frame；两端共用既有ActiveRoute中的保守长度上界，吸收车钩解算抖动。真实Core直线100步证明raw变化但floor实例及共享callback稳定，增长立即收紧、旧epoch不能缩短更新上界、缺/错帧不借缓存。
    不改Basic仲裁/真实车尾释放、双向闭塞、JS/模型或存档版本，不新增全世界扫描、资源registry或第二个planner。
  - 聚焦105项全部通过，含7个真实Core周期controller/Basic/signal组合：前视外红灯、同点第三方限制、短运营终点、相邻区域恢复、前车以及Dispatcher停止后的下一tick响应。
    Java API consumer编译和插件JAR隔离实际执行通过。独立checker仅修正2处旧测试fixture，不放宽runtime。
  - 最终离线代码/测试门禁通过：1764项，1757通过、7项基线skip，零失败/错误。
    Core 209（skip6）、Basic 389（skip1）、SimpleATO 351、BukkitPlugin 350、SimpleSignals 434、TunnelBridgeBuilder 31；
    前五个非Signals模块已在`--rerun-tasks`轮实际执行，最终复用相同源码结果，Signals最终434项实际执行。
    日志：`/tmp/javarailways-exit-clearance-full-check2.log`、`/tmp/javarailways-exit-clearance-final-gate.log`。
  - Core HTML Dokka已生成；Javadoc缺离线缓存`org.jetbrains.dokka:javadoc-plugin:2.2.0`，未验证，没有下载或改构建配置。
    `git diff --check`通过；真实Folia/客户端及规模性能基准未验证。提交后停下供用户审阅，Stage 2仍进行中。
- [x] 双向闭塞的两入口独立规则，保持一个共享资源。
  - 2026-10-06 用户批准本批：先补[所属契约](../../spec/simple-signals/backend/bidirectional-fixed-block.md)，再实现与独立检查。
    规则按作者完整路径的 FORWARD/REVERSE 绑定；每入口最多一个主信号，各自配置下游、提前时间、输出和显式模型。
    未配置该端不增加信号速度限制；已配置但无有效结果则入口0。共享一份占用与block资源，复用单向图、检查绑定和正常编辑事务。
    当时世界定义文件schema3、拒绝旧2（误升，现由上方版本纠正批次取代）；不改宿主API/世界文件、JS、信号Wand或多闭塞单线协调。
    研究与准确改动面见[方向规则研究](research/directional-fixed-block-signals.md)。九个生产文件改入口键、有向几何视图和声明索引；
    反向路径借用已有缓存，规则共享同一占用provider，保留方向有效性、原资源身份、车尾释放与净空门控。
  - 实现聚焦8套/160项实际执行通过，生产与全部测试Kotlin编译通过。独立checker未发现生产缺陷；
    补2项真实组合回归，验证Basic方向变化经signal epoch同时失效/恢复两端且无变化IDLE，以及同ID换入口fresh binding/旧新编辑范围。
    补充聚焦2套/21项实际编译与执行通过；总计新增16项测试。
  - 最终独立离线`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`通过，
    排除WebUI前端安装/构建，缓存Gradle9.5/JDK17+25，无下载和构建配置变化。
    43套/450项全部实际执行，零失败、错误或跳过；`verifyPluginJar`实际执行，Java consumer、最终编译与JAR构建复用未变缓存。
    直接检查JAR确认方向类型入包、无宿主类；`git diff --check`与9+9项上下文验证通过，没有独立lint任务。
    日志`/tmp/javarailways-directional-signals-focused3.log`、`/tmp/javarailways-directional-signals-review-focused.log`、
    `/tmp/javarailways-directional-signals-final-check.log`；XML在SimpleSignals/build/test-results/test。
  - 契约/README同步CURRENT，保留合法删除后缺失下游Unavailable。真实Folia/客户端与规模性能未验证；
    测试日志包含一次physics overload及既有Gradle/native警告，不能据测试通过声称延迟已验证。
    本批提交后停下供用户检查；既有TODO/docs-site、另一任务及未跟踪资产不纳入提交，Stage2仍进行中。
- [x] 对已有距离输入与信号循环依赖做有界方案评审，不重复添加输入模型或写死国家规则。
  - 2026-10-06 完成[距离与循环依赖评审](research/distance-and-cycle-rule-review.md)，独立checker核对实际编译器、规则输入及拒绝顺序。
    距离/区间长度已有；当前具体规则仅按下游速度阈值选档，没有距离自适应。物理环线与信号最终输出环是不同合同，后者在候选prepare中有意拒绝。
    当时距离配置策略与JS仍为待选方案；用户现已选择JS，见本文件顶部契约批次，不再扩展距离条件表。
    JS尚未实现；完整循环策略仍另外评审，不新增固定点、陈旧输出或重复扫描，不能把研究完成等同于实现。
    本批仅静态源码与文档检查，未改运行代码或执行构建；上述450项属于已提交版本纠正批次，不冒充新策略验证。

本批范围与验收见[修正批次](research/signal-comparison-corrections.md)。下文的历史默认进路完成项已被新决定取代。

## Start gate

- [x] Stage 1 public resource, restriction, inspection, applied-plan dependency/edit and owner-lifecycle APIs are complete, reviewed and archived.
- [x] Freeze the module/plugin/product name as `SimpleSignals`; the scaffold fixes the Java package root as `work.art1st.javarailways.simplesignals`, while persisted type IDs and script identifiers remain separate API decisions.
- [x] Load SimpleATO, Basic, Bukkit, persistence, interaction/render and shared module specs.
- [x] Keep runtime code current-model only. If pre-release protection definitions need importing, extend the separate auditable world-format migrator with dry-run and recovery rather than adding a Stage 2 runtime compatibility path.
- [x] Re-run the PRD convergence pass against the completed Stage 1 contracts and present the final Stage 2 summary for fresh implementation approval.

## Deliverable 1: complete SimpleATO extraction

Current extraction progress:

- [x] Move Timetable model, validation, runtime store, extension registry, persistence, simulation helpers and Bukkit callers into SimpleATO; remove the Basic owner without aliases or runtime fallback.
- [x] Move Line model, revisioned store, planner, infrastructure persistence, isolated simulation capture and Bukkit callers into SimpleATO; restore after SimpleATO Destination/Station materialization without aliases or runtime fallback.
- [x] Move Destination/Station model, stores, StationCall runtime/resolver, berth allocation, infrastructure persistence and Bukkit callers into SimpleATO; keep only the derived route-anchor projection in Basic WayPointStore, with no Basic aliases or mirror stores. Move Depot reset coordination to SimpleATO to avoid a reverse dependency.
- [x] Move Depot/DepotStore, rolling-stock assignment, runtime persistence and Bukkit callers into SimpleATO; keep Basic runtime as a strict revision/epoch pairing document without a mirror ledger or compatibility reader.

- [x] Inventory remaining ATO/operations production owners and callers in Basic/BukkitPlugin.
- [x] Move ATO mission, route progress, planner, constraint manager/types and world coordinator/diagnostics into `SimpleATO`; retain Basic's generic dynamics/actuation, route/perception snapshots, RailObject store and physical safety contracts shared with manual driving. Preserve manual fail-close through a generic Basic world safety interlock driven by the SimpleATO coordinator.
- [x] Delete the legacy `AtoProvider` registry/boundary/controller-switching path instead of migrating or replacing it. Signal extensions use the Stage 1 inspection and namespaced restrictive-output contracts.
- [x] Finish the separate operating-direction authoring/persistence seam: SimpleATO owns the sparse store, topology-bound overlay, authoring, route predicate and infrastructure persistence; Basic has no direction field or fallback owner.
- [x] Collapse Stage 2 persistence numbering to the real boundaries: infrastructure migrates once from outer V1/Basic V1 to outer V2/Basic V2/SimpleATO V1; Basic runtime moves once from V2 to V3 while additive SimpleATO runtime fields remain V1. Keep the converter pure and leave packaged file migration/recovery to the 09-24 task.
- [x] Create `SimpleATOBukkit` as an independent adapter library module with direct main-plugin composition and no descriptor, second `JavaPlugin` or internal service discovery. Package it through the main plugin's project dependency, and move SimpleATO Core-slot startup wiring plus SimpleATO diagnostics into it as the first real slice.
- [x] Audit the platform presentation boundary: keep the existing generic latest-only scheduler with its actual
  `BukkitPlugin` consumers, keep concrete signal rendering in `SimpleSignals`, and do not turn `SimpleATOBukkit` into a
  generic platform bridge merely to share one helper. Move only platform behavior with a real SimpleATO owner/caller.
- [x] Remove each old production owner after its replacement is wired; a final source audit confirms Basic has no Destination/Station/Depot/Line/Timetable/operating-direction/ATO-policy store or compatibility facade, while BukkitPlugin only composes or presents the SimpleATO-owned stores. Basic's remaining RailObject/WayPoint projections and generic pathfinding/actuation/resource code have distinct shared owners; protection-region ownership has now moved to SimpleSignals.
- [x] Move the immutable-topology search implementation into Basic and add the neutral forbidden-span/path-replacement contract. Compile SimpleATO operating direction into the same restrictions; keep complete replacement paths atomic in the query graph.
- [x] Add owner/world-scoped route-planning source registration through the existing Bukkit signal owner registry. Compile one stable source snapshot once per immutable-topology query, combine it deterministically with operating directions, and reuse the unified owner unregister/world lifecycle without a second token framework.
- [x] Migrate Mission, Station, Line, Timetable and the Bukkit route diagnostic to one SimpleATO immutable-topology planning seam, preserve precomputed timetable routes and typed direction failures, then delete the live-`RailView` planner and compiler.
- [x] Integrate the first concrete fixed-block signal source with the existing SimpleATO inspection declarations, single shared perception scan and dynamic braking-distance comparison; do not build another inspection runtime. Fixed blocks and protection regions compile route-local authority floors through existing inspection bindings, while the production fixed-block rule uses persisted configurable outputs and downstream thresholds.
- [x] Wire injected `SimpleSignals` fixed-block rule output through the existing namespaced most-restrictive restriction pipeline; signal-epoch invalidation marks only current same-source inspection bindings, each callback publishes from the complete active declaration set, and plugin output cannot widen Basic grants or existing ATO/ATP limits. Production concrete rule bindings remain a separate item below.

## Deliverable 2: SimpleSignals foundation

- [x] Scaffold the independent Java/Kotlin plugin, descriptor, compile-only host dependencies, capability checks and separate JAR packaging. The descriptor hard-depends on JavaRailways and declares the Bukkit API target; the current host exposes typed services rather than a numeric signal-API version, so startup validates the five required service capabilities instead of inventing string-version comparison.
- [x] Establish the planning-only plugin-owned world runtime: each exact host world load gets fresh candidate catalogs and exactly one namespaced fixed-route source; startup publishes restored definitions or an unwritten empty revision 0 when missing, corrupt/unregistered host worlds remain locally disabled, and topology changes fail closed until explicit republish.
- [x] Add the strict current-model SimpleSignals world-definition sidecar with stable namespaced IDs, canonical UUID/AB positions and complete fixed-route traversals. The plugin loads primary/backup and compiles the full candidate against the current host topology before publishing a usable planning source; missing starts empty without writing, while corrupt/orphan/rejected input installs only a rejecting fail-closed source and no usable runtime. Writes use same-directory temp, last-known-valid backup rotation and atomic-move fallback with no runtime legacy reader.
- [x] Move `ProtectionRegionDefinition`, boundary/catalog, graph-cut/interior derivation, route-window matching and validation tests out of Basic. The plugin catalog atomically publishes one topology/definition-revision snapshot and indexes candidate regions by rail; Basic still performs independent structural route validation.
- [x] Define SimpleSignals-private fixed protection route tables and compile each region table atomically into one neutral Basic `PathReplacement`. Publication validates gate correspondence, direction, continuity and interior containment; the cached source rejects stale topology/region publications and is registered by the planning-only world runtime.
- [x] Add the plugin-local protection definition patch transaction: expected-revision create/update/delete composition, fresh prepare/compile, exact old+new affected-rail edit permit, persistence-before-publication commit and typed failure diagnostics. Retained region/table revisions are monotonic, and every failure preserves the old disk/live state.
- [x] Connect the completed authoring transaction and diagnostics to SimpleSignals-owned commands: inspect/export/validate/apply via isolated strict-current-JSON drafts, shared pure validation, baseline revision and the single apply transaction. See [command contract](../../spec/simple-signals/backend/authoring-commands.md). Native Paper worker/entity/global scheduling, reload/disable identity guards, six-kind CRUD/duplicate validation, zero-mutation dry-run and bounded blocker diagnostics are tested. Final offline `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`: 161 tests passed, zero failures/errors/skips; JAR isolation verified. Real Folia smoke remains pending. Guided Wand remains a separate slice.
- [x] Remove Basic's protection-region request/claim/validator API and `PROTECTION_REQUIRED` policy after confirming the generic route-resource runtime is the production path. Keep and rename the generic tail-release controller used by that runtime.
- [x] Add plugin-owned fixed-block definitions, exact-topology compilation, route-local reverse index, derived Basic occupancy geometry, protection-interior non-overlap validation and current-model persistence. The patch model validates fixed-block edits, while live geometry publication remains gated by the atomic source-replacement item below.
- [x] Add the first plugin-local common section observation slice for fixed blocks: one host physical-epoch source publishes immutable baseline/changed occupancy and directed crossings, while unchanged blocks remain idle. Protection regions join only with concrete interlocking rules.
- [x] Define an atomic host transaction for live fixed-block geometry source replacement. Candidate sources compile before the caller commit callback; fixed-block sidecar/publication/occupancy runtime and host areas cross one simulation barrier, while edit permits still reject operationally protected rails before save. Wand/command callers remain separate work.

## Route and resource integration

- [x] 轨道公共方向归属 Basic，未部署存档直接修正；实现和独立验证完成：
  直接移动稀疏存储、中立读取值与通用方向限制转换；接通寻路、宿主 API、线路编辑/撤销、持久化和信号更新，删除旧 owner。
  Basic infrastructure保持format2并新增默认空方向列表；SimpleATO删除字段后保持format1。
  更新当前固定fixture，已有历史metadata转换只更正输出owner，不新增旧方向转换、兼容reader或中间版本测试。
  不实施09-24文件发现/dry-run/apply/自动升级全流程。
  旧已完成方向迁移记录仍属历史基线；新机制见[方向契约](../../spec/javarailways/basic/operating-directions.md)。
  - `BasicWorldData.operatingDirections` 是唯一稀疏存储；`OperatingDirectionStore`、只读 `RailOperatingDirections`、
    中立 `OperatingDirectionOverlay`、方向编辑换算和 `SavedOperatingDirectionV1` 迁入 Basic。
    SimpleATO 的旧类、字段和方向存档列表直接删除。Basic 纯搜索器仍只接收显式输入，没有新增搜索、兼容别名或双写。
  - 生产和隔离世界先创建已有 `SignalEpochRuntime`，构造 Basic store 时传入它的普通变化回调。
    保留相同值 no-op、锁内原子子集/轨道替换与锁外通知，没有 Basic→SimpleATO/Bukkit imports、新总线、监听生命周期或逐 tick 轮询。
    SimpleATO 寻路、初始方向、线路编辑/撤销与宿主信号读取全部使用 Basic；闭塞和手动控制机制不改。
  - Basic restore 在写入任何 Basic 数据之前检查非法 UUID、重复解析后 UUID 和缺失轨道引用；capture 拒绝孤儿记录。
    固定 current split JSON 验证方向只写 Basic、格式仍为2/1，Destination/Line/revision、Core 与三个 runtime payload 保持相等。
    独立复核补3项宿主读值回归，覆盖缺项/多项/错拓扑、读取中换拓扑与世界重载；针对性24项通过，无需生产修复。
  - 最终串行离线门禁：`:Basic:test :SimpleATO:test :BukkitPlugin:test :SimpleSignals:check`
    `:BukkitPlugin:compileSignalApiConsumerJava :WebUI:compileJava :WebUI:test`，
    排除`:WebUI:installFrontend`和`:WebUI:buildFrontend`，缓存JDK25/Gradle9.5，无下载或构建配置修改。
    Basic63套/381项（380通过、1既有跳过），SimpleATO44套/334通过，BukkitPlugin82套/336通过，
    SimpleSignals31套/306通过，WebUI4套/11通过；总计224套/1368项，1367通过、零失败和错误。
    唯一跳过是基线已有的`PathFinderTest$FindPathLoop`灯泡线测试，`@Disabled`未改变。
  - 最终BukkitPlugin tests和`verifyPluginJar`实际执行，其余未变输入复用此前通过结果；Java consumer此前实际编译、最终复用缓存。
    JAR和`javap`证明宿主方法返回Basic方向值，Basic包含迁入类，SimpleATO无旧owner类，SimpleSignals不夹带宿主类。
    独立复核无剩余问题；`git diff --check`和10项curated上下文校验通过。
    日志：`Basic/build/operating-direction-review-focused.log`、`Basic/build/operating-direction-review-final-gate.log`。
    没有live Folia/客户端或大规模性能验证；整体Stage2继续进行，其他未提交内容不纳入本批。

- [x] 落实在线编辑的统一失效策略：运营方向失配与结构失效均禁止 ATO 驶入，保留区间、进路及设备配置。
  - 已完成：方向失配明确拒绝 ATO、删除旧低速配置/分支；区域边界编辑不完整时局部禁行并保留配置，补齐后逐条验证。
  - 2026-10-05 完成事务影响驱动的跨层修正：Core 成功事务在新拓扑可见前通知受影响轨道；Basic/SimpleATO
    仅使相交依赖不可用，SimpleSignals 在 worker 重验关联区间并原子替换来源。无关冻结路线、授权、设备准备、
    继续申请和车尾释放不因世界 revision 增长而过期。编辑候选一致性、世界/列车/路线身份及新观测一致性检查保留。
    范围见[实施边界](research/section-topology-revalidation-batch.md)，机制归[局部重验合同](../../spec/simple-signals/backend/section-topology-revalidation.md)。
  - 独立审查发现并已修复：重叠双方遗漏修复依赖、失效保护进路丢失已知占用、后台来源副本覆盖旧来源较新的信号、
    连续无观测替换遗漏待更新信号，以及首次申请/折返使用旧物理观测。真实拒绝场景保留，不仅删除全局版本断言。
  - 最终离线门禁通过：Core 46套/208项（6跳过）、Basic 63套/389项（1跳过）、SimpleATO 44套/339项、
    BukkitPlugin 84套/350项、SimpleSignals 35套/358项、WebUI 4套/11项；合计276套/1655项，1648通过、
    7项基线跳过、零失败或错误。最后一次实际执行 SimpleSignals tests/编译/JAR隔离，其余未变输入复用先前结果。
    Java consumer、主插件shadowJar、diff check和12/14项上下文校验通过；没有独立Kotlin lint任务。
    日志 `/tmp/javarailways-local-topology-verified-gate.log`，新增局部重验类12项通过，独立审查无剩余阻塞。
  - 边界：没有真实Folia/客户端或压力测试。启动时无效存档仍可能使该世界安装失败；分割/合并后的信号坐标自动迁移、
    缺失附着轨道后的模型编辑恢复尚未实现。通知仍扫描待处理集合和浅复制局部映射，不能宣称所有提交成本只随命中数增长。
  删除 `disabled-block-speed-kmh` 和仅为低速降级存在的逻辑，不增加兼容策略；按实际调用清理相关接口。
  复用原有方向读取、变化通知、路线校验和资源生命周期；验证正反向、多轨道、无信号规则的闭塞均不能绕过拒绝，
  修复后恢复、旧占用不丢失、无关区间及手动驾驶不受影响。契约目标见[双向固定闭塞合同](../../spec/simple-signals/backend/bidirectional-fixed-block.md)。

- [x] 历史基线 `8ff013f6` 的方向一致性与低速降级修正，完成一个可独立审查的三模块批次；下述存储归属已由 Basic 方案替代，低速行为现已被上方已实现的新决策取代，以下仅保留历史证据：
  - SimpleATO 仍唯一持有运营方向。宿主公开接口一次读取相关轨道的完整方向子集；中央写入只通知实际变化轨道，
    沿用信号 epoch 的合并集合与轨道兴趣索引，无变化和无关编辑不唤醒相关来源，不轮询所有闭塞。
  - SimpleSignals 检查闭塞的每段 authored facing：单向要求每段仅允许该方向，双向要求每段均允许两向。
    反向几何用于识别经过而非自动许可；有效单向的反向资源申请在新增预留和设备准备之前明确拒绝。
    任一段失配禁用整个闭塞的正常 token、入口及保护区域后继授权下限，但保留定义、静态编辑依赖和已有 claim。
  - 既有检查绑定缓存带起止位置的区间限速，限制适配器继续使用原有制动曲线：入口前减速、内部维持、
    车头越过出口后结束。内部起步、反向和重复经过使用自己的路线范围；车尾仍按原队列释放资源，不因限速结束而提前释放。
  - 启动配置 `disabled-block-speed-kmh` 默认20.0，有限正数校验后一次换算；不提供热重载。
    已知降级对下游公开有效速度，包括未配置信号规则的资源型闭塞；未建立观测基线、未知或过期结果仍为0。
    降级限速仅约束ATO，手动驾驶及既有安全行为保持原样，ATS延后。
  - 普通物理更新复用不可变控制状态map；只有首次基线或相关方向修改才复制并更新它。
    独立复核进一步把区间声明ID集合缓存到binding，只查实际失效ID，没有变化时不额外遍历范围。
    另用先失败后通过的真实source/InspectionRuntime回归修复入口限制与区间限制的ID撞名，改用不同私有前缀，不改稳定声明ID。
  - 测试使用真实Basic资源与准备接口：反向拒绝没有新claim/pending或设备准备，正向对照确实启动准备；
    已持有闭塞在降级后保留，车头退出不释放，车尾退出才释放。另覆盖中间轨道失配、混合AB/BA、全部双向、
    重复范围与来源隔离、后继下限、方向更新IDLE、资源型闭塞基线、拓扑换根与新定义基线，以及配置/API/JAR。
  - 门禁结果和独立复核证据见下方。没有Core/Basic/SimpleATOBukkit生产修改、schema升级或兼容路径，
    不声称已验证真实Folia/客户端、完整回库任务或大规模延迟；整体Stage2仍在进行中。
  - 最终独立离线门禁：`:SimpleATO:test :BukkitPlugin:test :SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，
    排除`:WebUI:installFrontend`和`:WebUI:buildFrontend`，使用缓存JDK25/Gradle9.5，无下载或构建配置修改。
    三个测试任务均实际执行：SimpleATO338项/45套、BukkitPlugin332项/82套、SimpleSignals306项/31套，
    总计976项/158套，零失败、错误或跳过，57秒完成。插件JAR隔离检查实际执行，JAR包含当前默认配置；
    Java consumer在此前相关门禁实际编译，最终门禁复用未变输入缓存。日志`SimpleSignals/build/direction-review-gate.log`。
    独立针对性门禁88项通过，ID撞名复现先失败、修复后两测试类29项通过；最终复核无剩余阻塞。
    `git diff --check`及任务上下文校验通过；既有索引超出自动注入大小的警告仍在，两位worker均直接完整读取所需规范。

- [x] 实现已批准的单线单闭塞双向控制：默认关闭的 `bidirectional` 字段、反向路径准备与匹配、
  实际反向入口检查、同一 block token 的相向互斥/折返保留/车尾释放，以及反向区域后继和编辑保护。
  保持一份主信号输出，两端设备复用已有附属显示；不增加方向锁、占用区、规则节点或宿主 API。
  具体合同见[双向固定闭塞](../../spec/simple-signals/backend/bidirectional-fixed-block.md)。
  工具、测距和单线多闭塞协调继续推迟。六个生产文件复用既有框架，没有 Basic/Core/ATO 生产改动。
  针对性测试 134 项、7 个测试类通过；独立审查无需修复。最终离线
  `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除 WebUI 前端安装/构建）通过：
  295 项测试、30 个测试类，零失败、错误或跳过；JAR 隔离检查实际执行，Java consumer 编译 UP-TO-DATE，diff check 通过。
  日志 `SimpleSignals/build/bidirectional-fixed-block-check.log`。真实 Basic 类组合证明相向原子拒绝、车尾释放后再授权，
  以及停车折返不丢失原资源；完整回库任务与真实 Folia/客户端仍未验证。本项作为一批提交供用户审查，整体 Stage 2 未收尾。

- [x] Index protection-region gates/interiors by topology plus plugin-definition revision and rebuild only on catalog publication, outside the tick loop.
- [x] Index fixed blocks by exact topology root plus plugin-definition revision outside the tick loop.
- [x] Match fixed-block resources only on the frozen-route authority prefix: reuse one plugin-local directed-route projection,
  discover candidates through the rail reverse index. The original complete-block-only restriction is superseded by the reviewed station-inside-block requirement below.
- [x] Allow fixed-block internal starts/stops. Match the full frozen route before authority clipping, distinguish true entry from internal start, and reuse the existing token/grant/release queue. Real Basic+SimpleSignals admission tests prove an internal stop retains the token, strict forward append keeps the grant identity, head-only exit does not release, tail exit does, and later requests do not reacquire historical blocks. Direct region-successor floors clamp at internal station targets. The original internal-start authority-only declaration is superseded by the simplification below. Foundation commit `ac99ba27`: 68 focused tests passed; final offline affected-module gate passed with 1338 tests in 247 suites, 1331 passed, 7 existing skips, zero failures/errors; JAR isolation, Java consumer compile and diff check passed. Repeated frozen-route visits and return-to-depot block-token reversal remain the separate unchecked item below.
- [x] Simplify fixed-block inspection to actual entrances only. Remove the internal-start declaration ID/floor and handler skip; preserve block-resource contribution/held claims independently. Tests prove natural head-forward exclusion of a real passed entrance, no synthetic declaration at an internal start, future real entrances still activate, and front red/missing baseline plus stop/append/tail release remain correct. Basic's route origin and resource lifecycle are unchanged. Focused gate: 35 tests in 5 suites passed. Final offline gate (`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`) passed: 169 tests in 20 suites, zero failures/errors/skips; plugin JAR isolation, Java consumer compile and diff check passed. No live server smoke test was performed.
- [ ] Close repeated-visit and stopped return-to-depot integration without changing physical identity. The fixed-block and initial continuous bulb-line slices below are implemented; follow-up audit corrections remain listed below. Keep stopped return-to-depot reversal separate from a loop within one frozen route. The checked bidirectional item above now verifies single-block physical identity and stopped reversal; do not infer complete depot-mission integration from that bounded slice.
  - [x] Reviewed fixed-block slice: declaration uniqueness is `(stable ID, trigger route metres)`; active-state changes distinguish visits, dirty updates keep stable IDs, and the concrete source projects the nearest active visit per signal. Repeated block windows coalesce only the current requested prefix into one token with the latest covered release point. Inspection regressions and a real four-rail degree-2 Basic admission/extension/tail-release test pass; repeated-region selection, repeated turnout/diamond preparation and reversal semantics are unchanged. Focused gate: 66 tests in 7 suites passed. Final offline gate (`:Core:test :Basic:test :SimpleATO:test :SimpleSignals:check :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`) passed: 1344 tests in 247 suites, 1337 passed, 7 existing skips, zero failures/errors; Java consumer compile, plugin JAR isolation and diff check passed. No live Folia smoke was performed.
  - [x] User-approved bulb-line slice: repeated complete region windows and separate turnout acquisition/preparation after tail release. Basic rejects overlong requests before mutation with a neutral retry ceiling; SimpleATO retreats through existing floors and makes at most one local retry. Region selection uses directed tail-LEFT facts; simultaneous resource release cannot clear a later visit, and atomic unrelated definition replacements carry unchanged bindings' clearance facts, including consecutive edits before an epoch. Tests cover real Core turnout admission/preparation/release with driven physical observations, long lookahead, pending second preparation, conflicting trains, diamond dedup/latest release, repeated-entry selection, indicator-only observation, append and nonzero-clearance reversal reset. Focused runtime tests: 83 passed; production Dispatcher wiring suite: 21 passed (scripted admission results, not physical proof). Final offline gate (`:Core:test :Basic:test :SimpleATO:test :SimpleSignals:check :BukkitPlugin:test :WebUI:test :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`): 1363 tests in 252 suites, 1356 passed, 7 existing skips, zero failures/errors; consumer compile, JAR isolation and diff check passed. No live Folia smoke. Opposite-direction fixed-block ownership remains unresolved; no persistence/runtime compatibility added.
  - [x] Follow-up audit correction: when the next turnout use is still held, return the existing neutral retry ceiling instead of an unqualified busy result. The real Core/Basic regression starts at a short 350 m grant with a 50 m train and turnout uses at 340 m and about 510.71 m; exact prefix growth retains the first claim/selection, then tail clearance permits delayed second preparation under the same grant. Same-target, earliest blocker, tolerance, non-advancing ceiling, too-short-loop and true pending busy cases pass. Reuses current held identities and unchanged SimpleATO at-most-one retry; no new public API/state or compatibility path. Independent check found no additional in-scope issue. Focused gate: 99 tests in 8 suites passed. Final offline affected-module gate passed; reports total 1366 tests in 252 suites, 1359 passed, 7 existing skips, zero failures/errors (Core/WebUI reused up-to-date results). Java consumer compile, plugin JAR isolation and diff check passed. Basic lifecycle uses coherent driven observations; Dispatcher regression responses are scripted, not an end-to-end physical simulation. No live Folia smoke.
  - [x] Follow-up audit correction: unchanged Basic monitored areas now retain physical state through prepared replacements; the first new complete baseline includes real crossings. SimpleSignals inherits unchanged clearance on the first serialized new callback from one world-local last-processed runtime, not during edit commit. Worker-canonicalized unchanged route references avoid deep geometry comparisons on physics and no predecessor/event replay chain exists. Removed unused `replaceDefinitions` rather than wrapping it; no persistence/API compatibility path. Production-class composition tests reproduce old LEFT during a paused commit, old callback completion after publication, first-replacement LEFT, 20 unconsumed edits, failed commit, changed/restored region and new-baseline inspection0; Basic also covers ENTER+LEFT, revision-only continuity, boundary/topology changes, remove/readd, world/epoch rejection and IDLE. Independent review found no additional in-scope defect. Final offline affected-module gate passed: 1377 tests reported in 253 suites, 1370 passed, 7 existing skips, zero failures/errors (Core/WebUI reports reused up-to-date). Java consumer compile, plugin JAR isolation and diff check passed. Tests compose real tracker/host/plugin with constructed frames and admission values, not a live Folia run or proof of changed Basic authority.
- [x] Match protection-region windows on the exact frozen-route inspection binding, validate one complete entry/interior/exit traversal and compile an entry-to-exit authority floor. If the selected route has exactly one fixed block beginning at the exit, extend that floor through its exit; multiple same-point matches are conflicting definitions, not route branches, and fail closed. Basic derives the physical resources for the expanded route prefix, and no exclusive region token is added. See the [owner contract](../../spec/simple-signals/backend/protection-region-authority-transition.md).
- [x] Reconciled 2026-10-14: the existing neutral entry-clearance contract already supplies the no-fixed-successor policy. SimpleSignals contributes the region exit; SimpleATO raises the coherent request to exit + current train length + existing safety margin and checks actual reachable stopping space. No second moving-boundary scanner or recursive contributor is required. Mechanism: [entry clearance](../../spec/javarailways/simpleato/entry-clearance.md), [authority transition](../../spec/simple-signals/backend/protection-region-authority-transition.md); source and existing regression evidence: [closeout inventory](research/stage2-runtime-closeout-2026-10-14.md#2-the-moving-territory-post-exit-safety-mechanism-is-already-present). This closes stale implementation wording, not live-server validation.
- [x] Submit fixed-block tokens only as additive resources through the host contributor; rely on Basic to derive every
  rail/turnout/diamond physical resource and its existing atomic claim/release lifecycle.
- [ ] Add concrete flank/approach resources when the first interlocking rule requires them; do not invent generic placeholders.
- [x] Keep region identity non-exclusive by default; one connected four-gate region publishes two rail-disjoint route macros, Basic admits both physical footprints concurrently, and an overlapping request is denied by the actual rail/turnout footprint rather than a region token.
- [x] Derive permissive aspects only from a committed Basic grant and valid SimpleATO authority. The shared constraint pipeline keeps the initial/pending Basic authority at 0 m despite permissive plugin speed, applies plugin output only as a tighter post-grant limit, fails stale route bindings closed, and distinguishes an unconfigured block from a configured signal whose missing baseline publishes 0 m/s.
- [x] Hold each contributed fixed-block identity to its last matched exit and use the existing host cursor/release/extension lifecycle;
  do not create a plugin release queue.
- [x] Verify the unified owner-unregister gate with the concrete SimpleSignals resource/source identities: claim,
  dependency, non-empty restriction and active inspection blockers all reject planned disable before either
  registration set changes; clearing them permits complete owner removal. Forced disable removes dead callbacks,
  retains committed claims/dependencies and stalls only worlds with affected live state.
- [x] Integrate route-bound diamonds for conflict/inspection without cross-line continuation or selectable state. End-to-end admission proves both crossing rails derive one Basic diamond identity, conflict while held, and permit the other route after tail release; route validation still rejects crossing-line continuation and the crossing creates no turnout state.

## Reactive signal state and customization

- [x] Review the deferred reactive design: SimpleSignals owns a one-way provider-to-upstream-consumer invalidation chain; no fixed-point solver or host-owned dependency graph. Record the future Core active-continuation snapshot prerequisite without implementing it in this batch.
- [x] Publish Core's immutable active-turnout continuations in the coherent world frame and project them unchanged through Basic observation. Rebuild the turnout-port map only when topology or live selection revision changes; reject a candidate frame if the selection changes during epoch processing.
- [x] Historical implementation: built-in providers once used the following dependency/invalidation mechanism, which the
  JS-only correction above has now removed:
  precompile an acyclic one-way graph and evaluate only reached consumers, stopping unchanged branches without a world-wide scan.
  This completed mechanism is not the `TARGET` JS tree/cycle contract above; concrete aspect/limit rules remain separate.
- [x] Prove unchanged resources remain idle and stale permissive outputs cannot survive invalidation: unchanged host area state skips the plugin callback, unchanged rule output stops the branch, and rule/downstream failure publishes an unavailable zero-limit state instead of retaining an old permissive result.
- [x] Close the actual inspection-to-controller handoff gap (user approved the SimpleATO ownership move): Dispatcher publishes compiled declarations with its frozen-route mapping; each train's controller owns activation/baseline state, consumes signal invalidations and applies projections before its planner, using the existing single perception. Two real Basic/SignalEpoch/SimpleATO integration cases prove changed occupancy/readiness reaches the next physical control calculation with Dispatcher stopped, unchanged state stays idle, and callback failure prevents actuation; direct restriction-publisher tests are not the evidence. See the [inspection owner contract](../../spec/javarailways/simpleato/operational-routing-and-authority.md#343-缓存失效与响应式更新).
- [x] Extend the neutral signal-epoch wake-up seam with source-declared watched rail endpoints. Fixed-block downstream dependencies now declare only required turnout continuations; definition prepare walks that explicit directed path, derives distance and watched conditions, and turnout-only changes seed only affected rules. Missing, illegal, conflicting, cyclic or unreachable paths fail restrictive outside the tick path.
- [x] Propagate changed downstream state only as far as needed; stop when an output is unchanged and reject dependency construction that would introduce reverse/downstream feedback.
- [x] Historical foundation: the neutral state/output/publication primitives introduced before scripts remain reusable, but the
  persisted Kotlin rule authoring/compiler/evaluator has now been deleted in favor of catalog JS signal types.
- [x] Expose the neutral Basic active-admission value and compile/persist mutually compatible configured default protection routes without claiming them. The active value carries the frozen route and actual requested/committed authority boundaries; default-route runtime checks use precompiled turnout requirements.
- [x] Connect admission changes and physical turnout observations to one shared entry selector: request > physically-correct configured default > no route. Retain the old committed prefix during suffix preparation and remove tail-cleared route associations without ending the whole operating route. Match frozen routes once on route changes; unchanged selection state remains idle. Host routes latest-per-owner updates by cached rail interests, including removal/reversal, source replacement baseline and train disappearance independent of Bukkit follow-up. A cleared entry association does not release overlapping long resources still held by Basic.
- [x] Implement the three device forms' backend on that shared state: fixed-route signal, current signal and presentation-only route indicator. Reuse the indicator for distant previews through explicit source binding and player placement. Route-specific occupancy, mixed-kind one-way dependencies, typed inspection declaration IDs, additive schema-1 persistence and live definition patches are implemented. Rendering is tracked below; independent physical inspection consumption is the separately approved host slice above. Do not infer request state from claim owner strings, poll `liveClaims()` per tick or scan turnouts for non-default fallback routes.
- [x] 实现用户已选的 JS 输入／setter provider；[owner 契约](../../spec/simple-signals/backend/js-signal-rules.md)记录
  包结构、同步 signal epoch、只读树形前视、逐分支环终止、exact-once setter 校验、原子结果发布和启动 world 绑定。
  玩家放置、模型 materialize、热重载及 live Folia 验证仍单独跟踪，不因后端接线完成而视为已实现。
- [x] Verify protection-device deletion through the normal definition edit permit. Actual Basic registry regressions cover fixed-route/current-entry signals blocked by static route dependencies and live claims, unchanged disk/publication/sources on rejection, success after blocker release, and disjoint-line deletion. Normal Dispatcher ordering installs route protection before inspection binding and removes inspection before protection; subsequent worker updates filter removed captured bindings. No production defect was reproduced in this guarded lifecycle, so no deletion compatibility path was added. Owner contract: [definition edit impact](../../spec/simple-signals/backend/definition-edit-impact.md#5-不变量线程与版本). This does not establish arbitrary external-caller lifecycle safety or cancel an already-running callback.
- [x] Keep scripts away from mutable world objects and raw Basic claim mutation. Fresh restricted Graal realms receive only frozen
  JSON observations and evaluation-local exported setters; validated output enters the existing signal publication boundary, while
  missing/invalid packages, runtime exceptions and invalid setters publish restrictive unavailable/zero results. JS cannot claim,
  select routes or mutate Bukkit/Core/Basic state.

## Timetable, authoring and rendering

- [x] 本批实现主信号/附属信号机后端：附属定义、默认空集合存档、已有 patch/draft/inspect 接入、
  直接主信号引用及模型校验、旧新逻辑/摆放编辑保护，以及变化主信号到显示设备的预编译索引。
  复用现有 handle 保留、原子发布和清理，不新增信号规则、检查声明或资源。精确合同见
  [模型显示](../../spec/simple-signals/backend/model-presentation.md#主信号与附属信号机)。
  工具、测距、样式编辑界面、轨道可视化和单线运行扩展不在本批。
  聚焦门禁已通过：5 类 94 项（附属存档 4、原设备存档 4、草稿 4、显示绑定 18、world manager 64），
  零失败/错误/跳过；生产与测试代码编译通过。真实 Folia/客户端显示未验证。
  - [x] 独立审查未发现范围内问题，无需修补代码。最终 offline gate：
    `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，排除既有 WebUI 前端安装/构建；
    30 类 283 项测试零失败/错误/跳过，test 与插件 JAR 校验实际执行，编译与 Java consumer 为 UP-TO-DATE。
    `git diff --check` 通过；日志位于 `SimpleSignals/build/attached-signal-check.log`。
    沙盒首次启动未能加载 Gradle 本地库；在获准的构建环境重试后通过，没有改动依赖、构建文件或业务代码。

- [x] Close the reviewed collection/install race by protecting new physical routes plus old complete dependencies before current matching. Real Dispatcher + SimpleSignals matcher/publication + Basic registry composition verifies completed edits before the guard, denied edits after matching and checkpoint restore; injected first/replacement failures verify cleanup, restoration and failed-restoration retention/stall. This is deterministic ordering coverage, not live Folia scheduling/persistence-transaction coverage.
- [x] Reuse signal-plugin route matching/binding in existing selected-route timetable preparation and collect exact dependencies. Do not create a separate signal-audit layer, repeat authoring-time checks or pathfind again; transient red/occupancy waits, partial fixed-block traversal and repeated visits are not automatic apply failures.
  - [x] Fixed-block static matching reuses the existing matcher and registered token identities; partial internal starts/stops and repeated visits remain allowed, without live bind/update or another pathfinding pass.
  - [x] Protection-region static matching captures one immutable publication and reuses the runtime route-binding compiler. Complete no-table crossings and repeated visits are valid; configured tables require one exact region/entry/exit/path match. Internal endpoints, stale empty catalogs, conflicting successor floors and missing/ambiguous table paths fail before clocks/initial positioning, without claims, bindings or signal output. Real Dispatcher first/replacement tests prove cleanup/restoration; table mismatch is verified at publication/source level, not as a live Folia race. Focused gate: 42 tests/5 suites passed. Final offline gate: SimpleSignals 195, SimpleATO 330 and BukkitPlugin 290 tests passed with zero failures/skips; Java consumer compile and plugin JAR verification passed.
- [x] Extend the existing atomic applied-plan dependency declaration with exact registered SimpleSignals resources from selected routes (currently fixed-block tokens). Keep non-exclusive edit protection distinct from live claims; changed old block identities also enter definition edit impact. No artificial region/signal token, second registry, schema change or compatibility constructor. Owner contracts: [static dependencies](../../spec/javarailways/simpleato/operational-routing-and-authority.md#35-已选时刻表路线的静态资源依赖), [edit impact](../../spec/simple-signals/backend/definition-edit-impact.md). Focused ordering gate: 94 tests/4 suites passed. Final offline Basic/SimpleATO/SimpleSignals/Bukkit gate plus Java consumer and JAR verification passed; SimpleATO 330, SimpleSignals 189, BukkitPlugin 290 tests executed with zero failures/skips. Basic reused existing results (367 passed, 1 skipped).
- [x] Close the existing draft/apply/replace/draining/unapply lifecycle without another draining state machine. Existing tests cover draft storage/validation with no dependencies and guarded apply/replacement rollback; the new SharedAdmission regressions verify pending and granted requests, terminal-but-not-ended retention, edit/replace/force-discard rejection, successful end then removal, no later automatic cycle, typed removal failure retention/stall, and checkpoint recovery-only retention until explicit discard. Basic dependency/edit permits are real; admission completion and the neutral resource source are scripted test fixtures, not physical tail-release or live Folia proof. Owner contract: [Dispatcher lifecycle](../../spec/javarailways/simpleato/dispatcher-runtime.md#停止自动发车与解除编辑保护). Focused gate: 67 tests/3 suites passed; full SimpleATO gate: 333 tests/45 suites passed, zero failures/errors/skips. Core/Basic/Bukkit tests and Java consumer compilation were up-to-date in the successful offline gate; no runtime/schema/compatibility changes.
- [ ] Finish the remaining editor integration through the common edit permit; guided authoring remains separate below.
  - [x] Existing SimpleSignals six-kind definition patches use the common permit, and the admin apply command already displays the original blocker kind, owner/revision and rail/node/resource overlap with bounded output. `SimpleSignalsWorldRuntimeManagerTest` covers exact diagnostic content and truncation; do not add another formatter or mark that command UI as unimplemented. Contract: [authoring commands](../../spec/simple-signals/backend/authoring-commands.md).
- [ ] Add guided RailObjectWand interaction for create/edit/delete/inspect/validate; the admin JSON command workflow is complete above. Topology-format migration and its recovery points remain owned by the separate 09-24 migration task, not runtime command compatibility.
  - 独立道岔工具已提交。闭塞与保护区域 Wand 的已确定交互已完成本批离线验证，见[实施说明](research/section-wands-batch.md)；进路录制、信号机放置、测距及信号规则预设/模板推迟，不预留实现。
  - [x] SimpleSignals 提供 `/simplesignals wand block|region [create|delete]`。闭塞两点/旧点接续、自动方向、禁止道岔，独立删除模式；区域有向边界、吸附、限定轨道距离的自动补全与原区域修复。
    领域操作只生成已有 patch，复用 worker 保存、原子 publication/source 替换和 Basic 编辑许可。空/仅待补全边界不扫描整图；第一次实际区间编译产生的拓扑索引按 exact root 复用。
    世界存档新增默认空 `pendingProtectionBoundaries` 和区域默认 null `boundaryDraft`，不升 schema、不增加兼容或派生图存档。未完成区域保留原认可边界作为局部禁行依据，进路表/设备配置不丢失。
    修复时旧表逐条重验；失效进路或丢失下游目标的设备结果不可用。禁止规划、绑定和直接准入绕过缺口；方向失配同样明确拒绝，旧资源继续原车尾释放。
    复核修复了待补全边界编辑范围包含无关点、相邻轨道共同端点的零长度到达和删除关联、补放边界恢复身份、外部编辑过期选点等问题。
    最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 成功，34 套 / 345 项测试实际执行，零失败/错误/跳过；生产/测试 Kotlin 和 JAR 实际重建，JAR 隔离通过，未变 Java consumer 复用编译缓存。
    使用缓存 JDK 25 / Gradle 9.5，`--offline -x :WebUI:installFrontend -x :WebUI:buildFrontend`。证据：`SimpleSignals/build/test-results/test/TEST-*.xml` 与 HTML test report。
    新工具 33 项测试包括真实 Core 轨道与可控玩家/worker 调度；后端测试组合真实 Basic registry、方向存储和释放。不是 live Folia、客户端画面或大规模延迟基准。
    `git diff --check` 与 16 项 curated context 校验通过；原大型 index 超过注入长度上限的警告仍在，所需专题契约已单独列入 manifests。
    使用说明与契约已同步；地面子模型、可视化、信号机/测距工具、进路录制仍未完成。本批未修改 Core/Basic/ATO/宿主生产代码，Stage2 不归档。
  - [x] 主插件提供独立道岔 Wand：`/jr wand turnout`，左键来向、右键出口，复用现有 `selectTurnout` 和实际占用/资源冲突结果。
    根据命中弧长选择较近端点，只接受当前拓扑中的合法连接；成功/拒绝后重新读真实双侧选择，以局部短暂粒子和消息反馈。
    复用既有异步选轨和玩家调度；取消、换工具、换世界或换拓扑后旧查询结果不能扳动道岔。
    无新资源管理器、授权、周期任务、存档升级或兼容路径；Core、Basic 和 ATO 生产代码不变。
    使用说明见 [BukkitPlugin README](../../../BukkitPlugin/README.md)，实现机制见[道岔工具契约](../../spec/bukkit-plugin/backend/turnout-wand.md)。
    新增 12 项回归；独立复核补上跨世界往返时清空旧选择，并增加支线到干线的真实服务切换断言，无剩余范围内问题。
    修复后完整 BukkitPlugin 84 套/348 项测试实际执行通过，Basic 手动切换的4项聚焦测试实际通过，均零失败/错误/跳过。
    生产和测试 Kotlin 重新编译，Java consumer 编译复用未变缓存；shadowJar 实际重建，确认工具类、中英语言资源、WebUI JAR和默认 OP 权限入包。
    `git diff --check`与 curated context 校验通过；日志为 `BukkitPlugin/build/turnout-wand-review-fixed-gate.log` 和 `turnout-wand-review-gate.log`。
    取消测试覆盖实际生产判定函数，服务测试组合真实 Core/宿主；换世界事件接线通过源码复核，不表示完整 Folia 调度或客户端画面已验证。
  - [x] 提供 `/simplesignals wand route` 当前通路预览：左键入口、右键同区域出口、潜行取消。沿真实当前道岔选择追踪，不寻路、不扳动道岔、不申请资源、不保存进路。
    保留错误出口/尽头/循环/未开放通路的完整前缀和真实停点；仅相关道岔、轨道或所选区域事件异步重算，合并待处理工作并丢弃旧结果。
    worker 准备路径与箭头，玩家每20 tick最多发送512个已准备粒子；长路径循环分批，端点每批保留，逻辑路径不截短。完整 TextDisplay 可视化另做。
    关闭覆盖取消、换工具、退出、换世界、世界卸载和插件停用；修复迟到订阅/显示任务、旧玩家线程结果覆盖以及输入未变化时重复重算的问题。
    精确端点区域切口属于原有禁止配置，选点只处理合法同轨边界；删除不可达的跨接头匹配分支，没有放宽区域规则。
    独立复核补上相关轨道失效时的区域通知，真实 manager 回归先复现漏通知，再验证同定义恢复通知与无关编辑隔离。
    新增34项测试；最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 成功，40套/392项全部执行通过，零失败/错误/跳过。
    编译在聚焦检查中执行，最终门禁复用编译缓存；JAR隔离实际检查，Java consumer复用未变缓存。日志 `/tmp/javarailways-current-route-preview-check.log`，XML位于 `SimpleSignals/build/test-results/test/`。
    机制/复杂度见[预览合同](../../spec/simple-signals/backend/current-route-preview.md)，范围见[预览批次](research/current-route-preview-batch.md)。没有 Core/Basic/ATO/API/schema 变化，未做 live Folia/客户端或规模延迟实测，Stage2仍不归档。
  - [x] 接入 `/simplesignals route record`：保存当前成功预览的同一完整路径，首次建立固定表、之后追加；自动ID、相同完整路径无变化，同入口/出口不同支路可分别录制。
    冻结前校验有效预览及当前相关观测，冻结后正常道岔切换不改写记录；保留旧表ID、全部旧进路/defaults，绑定当前区域revision严格校验，不静默过滤失效旧进路。
    小型SimpleSignals适配生成原table upsert，复用manager唯一事务；沿用玩家Choice busy/active和关闭路径，提交判定只读原子状态。不寻路、采样曲线、申请资源或切换道岔。
    新增15项测试（manager9、handler6），聚焦107项/4套通过。真实manager/store/preview backend/Basic planner证明保存前后实际支路变化、重载保持、重复不写、追加/default不丢失和冻结语义。
    真实Basic registry的静态依赖/live claim拒绝相关区域编辑，包括未录支路；非法旧route、save/source/cancel失败保持旧disk/publication/source。
    最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 成功，40套/407项全部实际执行，零失败/错误/跳过。聚焦中编译实际执行，最终编译与Java consumer复用缓存，JAR隔离实际检查。
    日志 `/tmp/javarailways-route-recording-check.log`，XML在 `SimpleSignals/build/test-results/test/`。本批范围见[录制批次](research/current-route-recording-batch.md)，机制/复杂度见[录制契约](../../spec/simple-signals/backend/current-route-recording.md)。
    当时不改Core/Basic/ATO/API/sidecar形状，不加兼容、可选录制模式或新请求/结果层；后续已完成进路替换，并取消默认进路配置（见本文件顶部修正批次）。删除、信号机工具和完整可视化仍待后续。未做live Folia/客户端或规模实测，Stage2不归档。
- [x] 在 `SimpleSignals` 接入信号模型显示（实现与离线回归完成，真实Folia/客户端验证仍未完成）：把实体操作安排到目标线程，只更新实际变化并保留最新待显示状态，
  丢弃旧显示序号，世界卸载或插件停用时可重复安全清理。复用主插件平台 API 前先审查具体跨插件需求，
  不通过 `SimpleATOBukkit` 转发显示。
  - [x] 根据当前代码编写模型与轨道定位接口草稿，以及信号状态到模型的处理流程；包含伪代码、复杂度和必需测试。接口与数据类型仍待审查，不能据此认定渲染功能已完成。
  - [x] 提供按世界/轨道 UUID 查询不可变曲线的公开接口，并提取接收现有部件定义的内部普通模型入口；保留车体/转向架流程。这批不开放完整模型显示服务。查询复用 UUID 世界索引和 Core 世界局部读锁，不扫描世界轨道、不调用 Bukkit 调度；构造复用既有递归部件逻辑，根位置和车体 owner 改为显式参数，避免构造器复用时保留上次车体状态。独立审查未发现阻塞问题。离线验证：Bukkit 77 suites / 296 tests、TunnelBridgeBuilder 8 suites / 31 tests，零失败、错误或跳过；公开 Java consumer 编译与主插件 shadowJar 打包通过。回归覆盖真实 service/Core/世界索引组合和替身模型树，不证明完整世界加载保存、驾驶座交互或真实 Folia 实体显示。
  - [x] 根据实际部件/动画源码补充普通模型值契约提案：已展开的不可变容器/物品树、完整场景选择、同名动画广播与冲突拒绝、基础外观复位、完整资源键和普通模型零插值；记录原有残留外观、单动画槽、命名空间和缩放限制。该批只有文档与研究，实现由下一项跟踪。
  - [x] 实现公开不可变普通模型定义、校验与内部完整场景应用，包含基础外观复位、完整命名空间和普通模型零插值；独立 Java 调用示例验证值类型。保留列车入口及三 tick 平滑，删除未使用的旧配置普通入口，不实施旧 YAML 包迁移或时间轴播放修复。实现机制和证据边界见[主插件模型契约](../../spec/bukkit-plugin/backend/model-presentation-api.md)。独立审查无阻塞，补充 AIR 场景构造/恢复回归；最终离线 Bukkit gate 实际执行 80 suites / 313 tests，零失败、错误或跳过，Java consumer 编译通过。TunnelBridgeBuilder 回归复用既有 8 suites / 31 tests 通过结果，主插件 shadowJar 打包和公开模型类入包检查通过。普通模型使用真实 Paper ItemStack 与记录后端，Native 使用真实 adapter/替身实体，EntityLib 仅验证真实元数据自然光语义，尚未验证 Folia 客户端画面。
  - [x] 提供 `ModelHandle` 与第八个公开服务 `ModelPresentationService`：复用 `ModelPose`、创建时建立的场景索引、既有最新状态调度和附近玩家索引。提交只保存值；实体工作不持有提交锁，根位置变化先在旧所属线程删除再到新位置重建。接入实际服务注册、插件停用、世界撤回/同 UUID 重载和 Java consumer，不公开内部模型管理器，不保留草稿兼容。本批不接 SimpleSignals 模型包或信号绑定。
    - 卸载顺序已获用户确认：先卸载附属插件并完成其模型清理，再卸载主插件。`closeOwner` 返回非阻塞完成结果，也统计已经单独关闭但尚未删除的模型；删除失败仍可观察，不把 `onDisable` 返回或排入队列当成清理成功。没有新增卸载管理器。完整规则见[主插件模型契约](../../spec/bukkit-plugin/backend/model-presentation-api.md)。
    - 独立审查修复删除结果误判、玩家可见列表的删除竞争，以及登记玩家显示失败后的引用清理；无剩余阻塞。最终离线 Bukkit gate 实际执行 82 suites / 331 tests（服务 15 项），零失败/错误/跳过，Java consumer 编译、主插件 shadowJar 打包通过；TunnelBridgeBuilder 编译通过并复用既有测试结果。测试连接真实 service/builder/玩家索引和 Paper ItemStack，使用可控调度与记录后端；不证明 live Folia、客户端资源包或完整世界加载保存。真实平台 smoke 仍待后续验证。
  - [x] 明确信号模型包的 `default` 契约及配置片段：仅 SimpleSignals 模型包必填，初始缺状态或 `Unavailable` 时使用，未知明确外观仍报错；内部状态名称和宿主 API 不变。同步 README、需求与显示契约，并修正设计文档中主插件显示服务尚未完成的旧描述。本项仅完成文档，不表示已有包加载或信号实体。
  - [x] 确认并记录两种摆放方式、方向相关偏移、模型默认/玩家选择的倾斜规则，以及预设选择时复制为独立实例参数的语义。改变附着方向保留偏移值重算位置，原地旋转不移动摆放点；本项仅完成设计讨论和文档。
  - [x] 实现 SimpleSignals 的预设应用与纯摆放计算：两个源码文件复用 `RailPositionSnapshot`、`ModelPose` 和公开曲线，完成方向相关偏移、倾斜/竖直参照与位置独立的局部旋转；预设选择时复制参数，不增加动态继承或深拷贝模型树。两个测试文件使用真实曲线与独立推导的轴向期望验证 AB/BA、组合坡度/超高、局部旋转、实例独立性、重新应用、地面摆放及非法数值，新增 16 项全部通过。独立审查无阻塞或明显过度设计。
    - 最终离线 gate：`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，排除 `:WebUI:installFrontend` 和 `:WebUI:buildFrontend`。25 suites / 211 tests 全部通过，零失败/错误/跳过；模块测试、插件 JAR 隔离校验和 Java consumer 编译实际执行。复用本机 JDK25 与缓存 Gradle9.5，未修改 wrapper；首次 focused 构建意外触发既有前端依赖任务，无 WebUI tracked 改动，后续显式排除。不涉及加载器、存档、Wand、缓存/调度器或生产绑定，未做 live Folia/客户端显示验证。
  - [x] 按已批准的[模型包格式](../../spec/simple-signals/backend/model-packages.md)实现完整目录加载：三个源码文件读取多个模型、展开包内共享/内联部件，生成现有不可变模型定义、完整默认/普通外观及已解析预设。静态场景不要求时间字段，导入参数与列车同义且内联偏移仅应用一次；未知字段/null/重复键/别名/对象标签、非法路径与引用、循环及场景冲突均定位文件和字段后拒绝。五文件固定示例与 27 项回归通过。
    - 独立审查修正共享嵌套链逐层重复校验：先展开全部定义，再仅从共享根校验整棵树；用有序集合记录循环路径。补充未使用的嵌套错误、独立场景命名空间和调用方嵌套 Map 修改隔离回归，无剩余阻塞。测试通过仅测试依赖复用宿主已有 Paper 注册表初始化，不改变生产依赖或 JAR 内容。
    - 最终离线 gate：`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，排除既有 WebUI 前端安装/构建。26 suites / 238 tests 全部通过，零失败/错误/跳过；模块全量测试与插件 JAR 隔离检查实际执行，Java consumer 编译任务通过且复用未变输入缓存。源码 510 行、加载测试 592 行及五份 YAML；无包自动发现、设备存档、生产显示绑定、实体或 live Folia 验证。
  - [x] 将用户提供的 JR 四灯式灯头整理为[实际 demo](../../../SimpleSignals/examples/jr-four-light/README.md)：服务器模型包配置四种完整外观和左右预设，客户端资源包提供现代物品定义、四份网格与共用贴图，不覆盖普通纸张、不包含立柱或速度规则。demo 网格仅整体下移16个模型单位，并用部件偏移补偿；逆平移后的 JSON 与原始文件一致，PNG 逐字节一致，原模型工作区未修改。三项回归读取同一份分发文件，验证整包加载、资源引用/元素坐标、真实1.21.11资源包元数据，以及 AB/BA 安装朝向；不证明生产绑定或真实客户端显示。
    - 独立复核无剩余问题，三项 focused 测试实际重跑通过。最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 为27 suites / 241 tests，零失败/错误/跳过；模块全量测试和插件 JAR 隔离检查实际执行，Java consumer 编译复用未变输入缓存。只新增示例资产、测试资源接线、165行测试与文档，无生产逻辑或存档版本变化。
  - [x] 实现设备模型/摆放存档：三类设备复用可选模型配置、rail/ground 摆放和当前 sidecar；删除显示器重复位置，接入原有候选校验及旧、新轨道编辑许可，包括结构消费者的模型附着轨道。用户明确批准直接修正未发布 schema 1；不增加兼容字段或模型实例参数占位。具体合同见[信号模型契约](../../spec/simple-signals/backend/model-presentation.md)。固定 JSON 与实际 encode/decode 验证格式和值；manager 回归使用真实 Basic registry 验证缺轨道/越界/revision 拒绝、两类编辑 blocker、旧/新位置、ground 逻辑保护和失败原子性。
    - 首轮离线 focused gate 为 7 suites / 98 tests；独立复核无缺陷或明显过度设计，实际重跑 codec/manager 的 55 项测试通过。最终 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除既有 WebUI 前端安装/构建）为 28 suites / 248 tests，零失败/错误/跳过；模块全量测试及 JAR 隔离检查实际执行，Java consumer 编译复用未变输入缓存。`git diff --check` 通过；不改主插件 API/构建依赖，不验证包存在性、生产绑定、Wand、任意模型参数或 live Folia 画面。
  - [ ] 接入只处理显示变化的更新流程，验证实际画面、世界卸载和插件停用时的清理。设备存档不能代替生产绑定及真实 Folia 验证。
    - [x] 异步发现目录模型包，接通生产创建、完整信号结果到变化提交、定义替换及卸载清理。validate 只做纯准备，handle 在成功提交后创建；显示故障不回滚安全定义。无新格式、兼容、Wand、实例参数或热重载。详细机制与测试由[模型显示合同](../../spec/simple-signals/backend/model-presentation.md#生产接线与编辑边界)维护。初次离线 gate 为71项；独立复核修复多模型创建被新配置取代时误报显示故障，并以先失败后通过的回归确认。独立重新执行绑定12、manager58、服务发现2，共72项，零失败/错误/跳过；无剩余范围内问题。
      - 最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除既有 WebUI 前端安装/构建）实际执行模块全量测试、插件 JAR 隔离检查与 Java consumer 编译。29 suites / 267 tests，零失败/错误/跳过，`git diff --check` 通过。主插件/各模块仅作为构建依赖编译，未修改其源码或构建配置；已有 JDK 自动探测、deprecated API、JLine/JOML 等警告不在本批修复。Stage 2 保持进行中。
    - [x] 将模型对象生命周期与配置替换分开：世界持续保存实际 handle、上次场景/姿态和显示序号，各配置只保存设备选择和已计算姿态。规则编辑保留模型，位置/旋转变化向原对象提交，只有增删或换模型执行创建/关闭。工作线程对照当前配置与实际对象，差异遍历和集合合并在短提交锁外执行；该锁阻止旧回调覆盖保留对象。相同摆放和精确拓扑根可复用姿态，但全部外观仍须校验。纯准备、原事务和卸载清理不改变；无新 API、schema、兼容、热重载或实例参数。详细机制归[模型显示合同](../../spec/simple-signals/backend/model-presentation.md#生产接线与编辑边界)。
      - 回归直接记录 create/close 次数、对象身份、姿态和序号，覆盖规则编辑、移动/原地旋转、增删/换模型、无关定义编辑、同 revision 的不同拓扑根、部分失败/取消、反序启动，以及分别暂停旧计算与已开始的公开 submit。独立复核未发现生产缺陷，补齐缓存复用时非法外观拒绝且磁盘/source/permit/对象不变、原地旋转保留对象的断言。独立离线重跑绑定16、manager59、服务发现2，共77项，零失败/错误/跳过。
      - 最终 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除既有 WebUI 前端安装/构建）通过：29 suites / 272 tests，零失败/错误/跳过；模块全量测试、插件 JAR 隔离检查与 Java consumer 编译实际执行，`git diff --check` 通过。使用现有 JDK25/Gradle9.5 缓存，不改构建配置；测试日志移至 `SimpleSignals/build/signal-model-retention-test.log`。主插件和其他模块未改源码；已有 JDK 自动探测、JLine/JOML 与 Gradle 警告不在本批范围。Stage 2 保持进行中，真实 Folia/客户端画面仍未验证。
    - [ ] 真实 Folia/客户端画面验证；记录式服务和受控调度不能代替实际实体显示。

## Cross-cutting online edit latency

- [x] Narrow `SimpleSignalsWorldRuntimeManager.applyDefinitionPatch` edit-permit impact to changed old/new definitions, compiled geometry and structural dependency consumers, including connector paths and region/successor-block authority floors. Unrelated topology-root replacement compares existing derived values instead of treating every definition as edited. Reuse the existing permit/save/source transaction; retain whole-related-region and rail-granular protection. Owner contract: [definition edit impact](../../spec/simple-signals/backend/definition-edit-impact.md). Final offline gate: `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`; 150 SimpleSignals tests passed, zero failures/errors/skips, artifact and Java consumer checks passed.

- [x] Remove cross-world simulation-lock ownership from online signal source replacement and Basic
  edit-permit acquisition. A blocked commit callback must not prevent an old immutable signal epoch
  from completing; the next epoch alone observes the replacement.
- [x] Stage SimpleSignals JSON encoding, validation, temporary write and valid-primary backup copy
  before the final source/publication commit. The bounded callback may only recheck identity,
  atomically rename the staged primary and swap immutable references; backup rotation/cleanup follows
  publication.
- [x] Audit every RailWand create/replace/split/merge/remove path. Move final normalized
  `TopologyState` construction and any whole-world undo/persistence capture into asynchronous prepare;
  the platform/world commit may only validate the captured root, apply the prepared delta and publish
  the prepared root for the affected world.
- [x] RailWand create/rebuild/smooth/split/merge/remove/snapshot-restore now build the complete normalized
  `TopologyState` on the existing async worker. Prepared commits validate the exact base root plus only
  affected rail/endpoint results and publish the prebuilt root; stale preparation rejects before action,
  with no full-world fingerprint or synchronous builder fallback.
- [x] RailWand structural-edit undo captures both complete `before` and `expectedAfter` values in the
  asynchronous prepare pipeline. Core first freezes complete saved-rail values for the exact base/target roots;
  Bukkit then acquires the exact affected-infrastructure permit before a second worker stage prepares metadata,
  direction, render and operational-object values. The same permit remains held through owner revalidation and
  commit, so the prepared side-state cannot be superseded by an overlapping edit. The owner callback only consumes
  those prepared undo values and rejects a stale base root;
  it does not rescan the world to create the undo record. The worker now also stages the complete render sidecar
  and old-primary recovery copy; owner publication is an identity check plus atomic rename, and commit failure restores
  the old primary before releasing the edit permit. A per-world writer slot prevents two whole-file writers from racing,
  while stale record snapshots reject instead of overwriting a preceding disjoint edit. User-triggered application of an existing undo record
  deliberately keeps its complete owner-thread current-state checks until non-topology stores have one reviewed
  revision/stale boundary; optimizing that separate flow is not part of this item. Transient
  `SavedRailV1.activeTrackAtA/B` is now normalized out of structural undo before/after equality. Undo apply preserves
  the live selection when it remains in the ordered target connections, otherwise selects the first target or null;
  unrelated turnout switching therefore does not stale or overwrite the structural undo.
- [x] Prepared topology failure handling now compares immutable `O(delta)` base rail identities,
  endpoint continuations and crossing pairs: failures before mutation or after complete rollback keep
  the world healthy, while an incomplete rollback faults it. Snapshot restore reuses the exact topology
  root for active-branch-only changes and advances the revision only for structural changes.
- [x] Close the cross-world editing concurrency regressions. Existing host source/permit tests and SimpleSignals replacement-continuity tests cover paused source commits and cross-world simulation-lock independence; Core already covers a paused real prepared topology commit with another-world epoch. Two additional `RailWandTopologyEditPlanTest` cases pause real curve sampling while another world publishes actual moving-train physics, prove candidate invisibility before commit, and prove stale commit never enters its mutation closure or changes the current root/inventory. Owner evidence boundary: [online edit concurrency](../../spec/bukkit-plugin/backend/lifecycle-and-threading.md#在线编辑并发验证边界). Focused gate: 11 Bukkit + 18 Core tests passed (Core reused its successful focused results on final retry); full Bukkit gate: 292 tests/75 suites passed, zero failures/errors/skips; Java consumer compilation was up-to-date. No production/schema/compatibility changes. Full player/Folia scheduling and live-server latency benchmarks remain unverified.

## Validation

- [ ] Complete module-boundary/one-owner audit for Basic, SimpleATO, SimpleATOBukkit and `SimpleSignals`. The current root gate closes only the declared direct main-project reverse edges and selected artifact references; it cannot prove semantic ownership or public-contract-only use. The known SimpleSignals `ThreadGuard` leak is now closed through `core.api.threading`, but the broader semantic ownership audit remains open.
- [x] Packaging checks for the independently installed `SimpleSignals` plugin prove it has its own descriptor/JAR, compile-only host dependencies and no shadowed host classes. The same gate proves the embedded `SimpleATOBukkit` library has no plugin descriptor or `JavaPlugin`/`ServicesManager` lifecycle, while `BukkitPlugin:shadowJar` contains `SimpleATOBukkitModule.class` under the sole JavaRailways descriptor. It does not treat the adapter library JAR as another server artifact. `SimpleSignals:verifyPluginJar` remains the focused artifact check; live Paper/Folia dependency resolution and enable remain separate.
- [ ] Region tests cover simple turnout, multi-turnout throat, leak, ambiguity, direction, topology invalidation and unsupported overlap.
- [x] Resource tests cover mandatory Basic physical floor, valid additions, omitted/invalid additions, disjoint paths and atomic conflict denial.
- [ ] Fixed/moving transition tests cover whole next block versus exact post-exit boundary and sufficient train-tail clearance.
- [x] Reactive tests cover inspection lead-time cache invalidation, host time-to-distance and braking calculations, affected-only dependency propagation and idle unchanged state. The cross-layer fixed-block test now proves an ended route drops its cached maximum and a later binding reads the newly published SimpleSignals lead time; existing scheduler/runtime/one-way-graph tests cover motion conversion, braking activation, active-route source replacement fail-close, affected-only propagation and unchanged IDLE behavior.
- [x] Constraint tests prove plugin output only tightens authority/speed and stale/exceptional state fails restrictive.
- [ ] Lifecycle tests cover enable/disable, missing capability, world unload/reload, live-grant recovery and rendering cleanup.
- [ ] Persistence tests use fixed current-model fixtures plus corruption/orphan cases. Add a legacy fixture only for a separately approved sidecar migration support window.
- [x] Timetable tests cover audit diagnostics, atomic applied-plan dependency apply/replace, draining and edit blocking without treating dependencies as train claims. Evidence and the scripted-admission/physical-runtime boundary are recorded in the lifecycle item above; live Folia validation remains separate.
- [ ] Run focused module tests, then `./gradlew test`, plugin packaging tasks and Folia smoke checks.
- [ ] Run `git diff --check`.

## Reviewable commit order

1. SimpleATO production slice migrations, one owner at a time.
2. Embedded `SimpleATOBukkit` library scaffold and platform adapter extraction.
3. `SimpleSignals` scaffold and persistence boundary.
4. Protection-region domain/index migration from Basic.
5. Generic Basic intent integration and removal of old protection policy.
6. Fixed blocks and SimpleSignals policy.
7. Reactive state/dependency scheduling.
8. Authoring/rendering and optional customization runtime.
9. Timetable audit/applied-plan dependency integration and lifecycle closure.

Each commit must explain which runtime flow it changes, the mechanism used, the remaining transitional owner and the focused verification performed. Stop for user review after every commit.

## 进路自动生成与鼠标替换：当前批次

- [x] 创建完整的新保护区域时自动生成各有向边界组合的最短进路；复用现有异步编辑与校验发布。
- [x] 将录制改为左键入口、Shift + 左键出口、右键确认；移除录制命令及旧的潜行取消手势。
- [x] 将保存由单纯追加改为对应入口—出口组合的路径替换，保留已有进路身份及引用。
- [x] 为多通路组合提供出入口警告信息；先记录共用视觉规范，再接入边界警告显示。
- [x] 覆盖唯一/多通路、方向限制、等长选择、不可达、鼠标交互、绑定保留、编辑拒绝及修复不覆盖测试。
- [x] 明确离线验证与真实客户端显示验证的边界；独立审查通过，无需代码修正。本批提交后停止等待用户检查。

聚焦检查实际执行133项/6套，全部通过，新增20项。独立完整门禁通过：41套/427项，零失败、错误或跳过。
完整测试与JAR隔离实际执行；生产/测试编译、JAR及Java consumer编译复用缓存，本批编译已在聚焦检查执行。
日志`/tmp/javarailways-automatic-routes-check.log`；未验证真实Folia、客户端画面或规模延迟。
搜索和警告几何在worker，每个入口一次最短路搜索；替代通路用移除路径连接后的局部可达性检查。
没有新增每tick图遍历或schema升级；完整TextDisplay、信号机Wand、测距及单线多闭塞协调不在本批，Stage2继续进行。

## Rollback points

- Keep module extraction, protection migration, route integration, reactive policy, persistence and rendering in separate commits.
- Do not delete the old owner until the new owner is wired and parity tests pass in the same reviewed slice.
- Do not enable permissive aspects until resource/grant/constraint integration tests pass.
- Leave `SimpleSignals` disabled for a world when persisted state or topology derivation cannot be validated safely.
