# Decorations backend

适用于独立 Decorations Gradle 模块。公开宿主 API 在 `work.art1st.javarailways.bukkit.api.model.extension`；禁止链接 host config/model/render 私有实现。

CURRENT：Decorations hard-depend JavaRailways，soft-depend SimpleDisplays。普通 JAR 不打入宿主、SimpleDisplays API、Kotlin/serialization/YAML runtime。`verifyPluginJar` 检查 descriptor 与 owner class 边界。只实现 display，不迁移 vehicle collision platform 或普通 item_display pantograph。

`DecorationsPlugin` 先发现可选服务，再注册 `decorations:display` provider，检查已有包失败并记录诊断；disable 先关闭注册，再关闭可选服务连接。DisplayPartConfig 严格校验 category/interior/width/height 和可选template，payload schema-version 为 1。template缺省null/省略保持类别默认；显式值必须为有效模板ID，并在validate/open对exact owning-car captured resources校验，未知或缺resources立即拒绝，不能回退掩盖错误。优先级屏幕template > 包类别default；两者均缺保持无生成内容。

manifest 的 `extensions.decorations:display.directory: decorations` 捕获目录下 `displays.yml` 与 `displays/*.xml`。DisplayResourceParser 只读不可变捕获 map，无磁盘 IO；严格拒绝重复键、未知字段、非法相对路径、无效 UTF-8（宿主捕获）、坏引用。templates/defaults 结构不变，模板 file 相对扩展目录。XML/JS 原文保留；可用的 SimpleDisplays 在 provider 校验中编译全部模板，拒绝坏 XML/布局/JS 语法，但不执行 JS。无可选服务时继续仅元数据校验。

OptionalSimpleDisplays 保持 API-free 加载边界：仅启用的命名 SimpleDisplays 插件存在时延迟加载 typed binding。binding 验证 Bukkit service owner 和 API 类身份。缺失/不兼容不阻止 Decorations 或列车加载。provider disable/service unregister/Decorations disable 释放连接和 listener；不自动重连，替换后需重启 Decorations。缺失 API 的独立 classloader 测试不能用 null service 替代。

CURRENT：provider 实现公开 PartExtensionRenderer，宿主 worker 调用 open/draw/close；Decorations 只返回 immutable PartExtensionFrame，不取得实体、World、Car 或 backend handle。实际挂载复用宿主 native/EntityLib 的 owner、观众与清理路径。测试命令：`:Decorations:test :Decorations:verifyPluginJar`；宿主改动另运行 BukkitPlugin 测试/打包。

定义及资源 schema 位于 Decorations/schemas，使用示例/构建说明见 Decorations/README.md。

## 模板编译缓存（CURRENT）

`OptionalSimpleDisplays.validateTemplates(String, DisplayResources)` 保持 API-free；实际 typed binding 通过 `DisplayService.compile` 编译，ABI 必须支持 `DisplayTemplate : AutoCloseable`。当前独立 API 基线 410af2e，不能只凭 SNAPSHOT 坐标推测产物版本。

每连接最多 64 项 exact-source LRU，当前无 icons 声明，源码相同才复用。缓存只记忆校验，不按 package-name 发布 active 状态，不供挂载直接持有；运行期不借用此缓存，每个挂载session独立持有一次compile的template并重复draw，退休后close。坏候选的部分成功编译可留在有界缓存，不改变已发布 snapshot。重复并发编译关闭 loser，退休后完成的对象关闭且不能安装。compile 和 close 都不能在 cache lock 下执行。

close/eviction 逐一尝试释放并聚合报告，不能因一个失败跳过后续对象，也不能关闭共享 DisplayService。service unregister、provider disable、Decorations disable 均清空缓存/引用。连接跨 reload worker/生命周期线程读取使用明确可见性。异常保留 package/template/file 和真实 cause；编译错误不能伪装成缺少服务。定向回归覆盖有界/LRU/重试/部分成功、并发 duplicate/retire、任意 callback 不持锁、API 缺失的新增入口和真实 Paper 语法拒绝/保留旧快照。

## 动态会话（CURRENT）

使用 exact owning-car source snapshot 的资源及 category default；不按引用 part 的 namespace 查找、不读当前磁盘、不按 package-name 覆盖来源。provider.open返回真实PartExtensionRenderSession，静态provider缺少服务/默认模板返回null；实际插件注入runtime store时，有captured catalog即建立路由，允许无默认屏幕由覆盖激活。每个有效选中模板按需私有compile一次，后续draw(DisplayContext(data,elapsedTicks))，不借validationLRU。输入Json递归转换为真实map/list/null/bool/finite number/string；typed ABI确认draw(DisplayContext)，API-free bridge不得暴露SimpleDisplays类型。

连接追踪拥有的sessions；退休立即disposed，拒绝late open/late draw，活动draw的finally承担延迟close，idle close在锁外执行。宿主串行worker上的session.close可以观察已开始的close完成或终态失败；生命周期caller不等待活动guest退出。每个模板实际close一次，保留原异常及suppressed清理失败，不关闭共享DisplayService。已退休session输出EMPTY；重新连接仍需重启Decorations。

等比居中：u=min(physicalWidth/logicalWidth,physicalHeight/logicalHeight)，x=(draw.x-logicalWidth/2)*u，y=(logicalHeight/2-draw.y-draw.size)*u，z=draw.zIndex/63*min(physicalWidth,physicalHeight)*0.01；glyph scale=draw.size*u*40/9（三轴一致）。logical/derived geometry必须有限且尺寸为正；最多1024文字。换算属于适配层，不由backend猜测。draw错误不关闭仍有效的session，后续输入可恢复。

宿主当前每10source ticks至多请求一次，ctx.data.train/car为同physics frame的基础值；XML使用trainId/trainName/trainType/carId/carNumber顶层string aliases。精确字段见Decorations/README。运营停站通过宿主只读投影提供。逐观看者挂载内容仍待实现；Bukkit PlaceholderAPI移出本任务。

## 实际运行线外关屏（CURRENT）

`DisplayPartProvider.open` 返回的所有 session 路径统一检查 detached 输入：
只有 `operational` 与其中 `currentRun` 均为 JSON object，才调用模板 `draw`。
没有实际运行线时返回 `PartExtensionFrame.EMPTY`，不执行 XML/JS，文字、图标与模板背景都没有生成输出。
仅有 active 运营车次或预先 BEGIN 的显示字段不足以亮屏；不通过模板自行判空实现这项规则。

关屏保持 session、unit 样式绑定、revision、视距策略及关闭委托；隐藏/off 时仍转交轻量 `update`，
再次进入 RUN 可复用 session。宿主在接收新的运营上下文采样时通过既有 context invalidation 清除旧生成叶子、
取消旧 generation 的发布资格；Decoration 不新增取消机制，不修改 SimpleDisplays 库、车身或原始子树。
清屏沿用现有采样/owner 应用时序，不承诺命令发出瞬间即完成。

回归必须同时覆盖 direct/routing 两条路径、静态和脚本式输出、active trip 保留而 currentRun 消失、
未知/null 输入、恢复、关屏期间样式修改及绑定保留。缺运行线时 delegate draw 次数不增长，返回帧必须为空；
不能仅断言字符串别名已清空。没有配置、持久化或版本变化。


## 车内视距（已实现，整体验证待统一执行）

Decorations/config.yml 的 displays.interior-distance.default-blocks/max-blocks 默认32/128，严格已知mapping/key和数字校验，0<=default<=max<=4096且max>0。不添加 schema version 或开发旧配置 reader。玩家 ConcurrentHashMap UUID 覆盖仅本次在线有效；quit/disable清空。/decorations display-distance [blocks|reset]，permission decorations.display-distance 默认true，命令在玩家 entity scheduler 执行，改值后调用注册句柄 refreshAudience(UUID)。

provider 只对 interior session 注入 PartExtensionAudiencePolicy，callback仅从线程安全设置读取UUID对应距离；车外返回 UNRESTRICTED。宿主负责玩家眼睛到屏幕最终位置的距离与 generated text audience，禁止附属插件导入 host render/util/private 类。原始 private template session 的 draw/close 仍委派给可选连接原owner；session wrapper 不接管共享service。

本批用户要求全部做完后统一验证，目前仅编译与准备回归案例，不宣称测试或Paper/Folia/client通过。Culling可行性评估记录在主任务research/interior-display-culling-assessment.md，未启用额外背面/视锥/遮挡规则。


## 固定单元样式覆盖（已实现，整体验证待统一执行）

DisplayStyleService 经 Bukkit ServicesManager 公开 immutable 查询及 set/reset；key 为 world UUID + actual input.data.unit.id + category，禁止 train name/car index 推断。RuntimeDisplayStyles 验证所有 matching captured catalogs 后原子 set；availableTemplates 为交集。无绑定不可新 set；最后解绑保留覆盖供重建继承，可 query/reset。反向/解编/重联保留；accepted world unload/disable/restart 清空，无持久化或版本变化。world epoch 拒绝旧 session 的晚绑定。

选择顺序：unit override > config.template > captured category default。RoutingDisplaySession.update 只读 detached unit 元数据，隐藏也绑定，不执行 JS；draw 按需打开 private template，真实选择改变时在同一 worker 关闭旧模板。新 catalog 缺失已选 override 必须局部诊断并允许 reset，不能默默回退。store callback 锁外调用 refreshRendering；仅 effective ID 改变增加该 binding revision。

/decorations display-style <unitId> <category> [<templateId>|reset]，permission decorations.display-style 默认 OP。玩家 world/权限/反馈在 entity scheduler；tab 不读 live player。quit 只清距离。MONITOR+ignoreCancelled world unload 清样式，cancelled unload 保留。命令与公开 API 共用 store，不改 SimpleATO 数据。

## 安装侧与有效开门侧（源码已实现，统一验收待执行）

DisplayPartConfig 可选 `door-side: left|right`，严格小写；省略表示未声明。基准固定为安装车辆/model 的 HEAD 左右，不随反向修改；payload schema-version 仍为 1。Decorations 在 typed SimpleDisplays draw 边界向 detached 数据添加 `ctx.data.display`，不修改输入、宿主公共结构或服务生命周期。字段为 `{doorSide: left|right|null, openingSide: this|opposite|both|none|null}`。

仅从 `operational.doorSide` 的 LEFT/RIGHT/BOTH/NONE/null 和 `car.operatingEnd` 的 head/tail/null 推导：LEFT/RIGHT 在 tail 时先交换为固定车体侧，再比较安装侧；缺少已知 end 或安装侧返回 null。BOTH 始终 both，NONE 始终 none，不要求安装侧或 end。未知值返回 null；不从 transform、车号、metadata 或未来停车站猜测。事实仅表示有效开门侧，不能证明门动画已打开。回归覆盖原始输入不变、反向前后同侧、相反安装侧、both/none/unknown 和真实 typed DisplayContext 交付；本批只准备测试源码，按用户要求暂不执行测试。


## 独立样式包与图元资源（已实现源码，统一验收待执行）

启用时从 `plugins/Decorations/styles/<bundle>/displays.yml` 与引用XML捕获不可变样式。复用DisplayResourceParser，目录包名限定小写字母数字/连字符/下划线，模板ID必须以 `<bundle>.` 开头，独立包不能声明类别默认；符号链接、非法UTF-8、非普通文件、过量条目/字节和坏引用拒绝。没有目录等于空catalog，不添加版本或开发旧格式reader。编辑文件后需重启Decorations，draw不读磁盘。

Provider在validate/open把该启用期catalog与owning-car captured资源合并，重复ID拒绝；config.template和runtime override均可显式选择外置ID，原包default仍由包本地定义。缺少SimpleDisplays仍保留资源与树；坏显式引用不能回退。S100门区显式引用 `cabin-route.route`，前显由车包内部XML提供。

矩形改用SimpleDisplays `<rect>` 输出的原版U+2588字形（`minecraft:include/default`），不用自定义矩形PNG。该原版ASCII字形可见8×8、advance9、ascent7，原生TextDisplay墨迹X[-0.0875,0.1125]、Y[0.025,0.225]格；尺寸0.2×0.2、中心偏移(0.0125,0.125)。SDL计算非等比scaleX/scaleY及原点补偿；Decorations保留这些独立轴缩放，不再强制XYZ一致。旧库缺少scale getters明确报告ABI不兼容，不静默压缩。普通span保持现有名义字号语义。圆环/圆点仍用自定义字体，中心偏移必须以size/9补偿，不能把provider advance当作墨迹宽度。详细原版证据见主任务research/vanilla-rectangle-metrics.md。

适配层透传draw.key，并按固定CSS z-index映射屏面正z，范围0..63；深度只由层级与屏幕尺寸决定，与帧元素数量/次序无关。同层重叠由模板作者处理。原始静态XML图元使用显式id或原始DOM路径，脚本新增图元必须有显式id；帧key必须唯一。typed ABI同时检查getKey/getZIndex，旧库应报告不兼容。车内下一站圆点持续保留节点，仅切换空文字；背景、线路、圆环、圆点和文字使用固定层级。客户端字体覆盖、最终深度精度、字形可读性和方向仍需游戏验收。构建脚本 `scripts/display-assets/package.py` 生成车包、独立样式包和保留客户端overlay的完整资源包，不部署服务器。
