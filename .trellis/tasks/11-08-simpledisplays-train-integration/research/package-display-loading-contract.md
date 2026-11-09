# 显示模板包加载与快照批次（用户已批准）

## 边界

本批从主任务下一步计划执行：可选displays.yml的严格解析、包内XML原文加载、默认类别映射、既有来源包快照/hash固定。生产实现仅在ddf5/JavaRailways，独立SimpleDisplays410af2e不改动；配置一致性子任务保持planning。XML/JS编译缓存、样式覆盖优先级、PAPI、实际实体/渲染后端、运营数据、视距与命令均为后续批次。

## 输入与数据合同

原任务草案已迁入正式 `schemas/train-model/displays-v1.schema.json`，以正式定义为准。包根可选displays.yml：config-version1；templates为必填map（可为空），key为非空无空白的本地ID，value只允许file；defaults为可选category→本地模板ID的map，缺省为空。所有字段严格校验，拒绝重复YAML键、未知字段、错误类型/版本、空白ID与坏引用。XML承载内嵌JS，不支持单独JS文件或冗余type/尺寸。类别不强制预设enum；同一模板可供多个类别复用，不要求每个类别当前一定已有安装点。

file必须是非空、包根相对的正斜杠XML路径；拒绝绝对路径、Windows盘符、反斜杠、空/点/父级路径段、非.xml后缀、越界符号链接、目录/缺失/不可读文件。真实路径需留在同一源包内；声明存在但不合法时不能当作未配置。displays.yml自身存在但非普通可读文件也要拒绝，不静默跳过。源码按UTF-8读取并保留实际内容（包括script），不在JR重写XML编译器。此阶段不编译/执行XML/JS；语法和布局校验属于下一模板编译批次，由SimpleDisplays负责。错误需含来源包/模板/文件或defaults类别等定位信息。

不存在displays.yml等于空显示资源；仅在加载/重载阶段读文件，无tick IO，不依赖可选服务。保持公共YAML工具的其他调用行为；如需严格重复键选项，增加明确的可选参数而非顺手改变所有旧格式的解析政策。

## 来源与快照

LoadedTrainPackage增加明确默认空的显示资源，含模板file/source和defaults。SavedTrainPackageSnapshot增加同一默认空字段，复用TrainPackageSnapshotStore，不增加store/版本层。snapshot build排序模板/默认映射并复制输入；将主包快照和每个来源车厢包快照的两条ModelManager构造路径都接入。跨包组合不能把主包资源当作车厢来源资源，无跨包fallback。

内容hash覆盖XML原文、模板声明及默认映射，map插入顺序不影响hash；改源文件影响后续reload，新快照与旧快照独立。Store.load重算hash必须纳入显示资源，篡改内容应拒绝。空资源不能改变无显示基线的canonical序列化/内容hash：可用EncodeDefault.NEVER对默认空字段省略，配合固定baseline fixture。数据类型构造/JSON解码需保持引用/身份不变量；快照内只存必要的值，不保存File、service或编译后的对象。

## 版本决策

新独立displays.yml文档从约定初始1开始。已有manifest保持2，已有包snapshot保持1；新增明确默认空字段为兼容性添加，按版本政策不升版，不引入legacy reader/runtime兼容路径。空字段省略同时保持旧无显示snapshot的hash。这不宣称已有格式曾发布/冻结；没有证据时不猜测发布历史。

## 预期修改

模型配置域：新增显示资源值类型/loader，PackageLoader和LoadedTrainPackage接入；YamlLoading如有需要加opt-in严格键选项。持久化域：SavedTrainPackageSnapshot、TrainPackageSnapshotStore、ModelManager两条build来源接入。正式schema、使用文档/任务材料和针对性测试同步。既有内联部件/动画格式不改。

## 验证

- 真实PackageLoader读取普通及带script XML、空/缺省配置、一模板多类别、错误字段/type/版本/defaults、YAML重复键、路径边界及符号链接。
- 固定无显示snapshot fixture读入、既有hash不变；显示数据round-trip、canonical map顺序、源码/defaults变更改hash、存储篡改拒绝；实际ModelManager来源车厢快照隔离。
- 实际ModelManager reload更新XML发布新快照，旧快照不变；坏文件或引用保留旧catalog，同时其他有效包可更新。
- BukkitPlugin定向测试、完整模块测试、shadowJar和受影响开发模块编译；无显示API类进入host包。已有独立ProgramExpressionsTest失败需如实区分，不把它算作本批通过。

开发前已从未修改的编译基线捕获fixture：/tmp/simpledisplays-display-baseline/package-without-displays.json，hash=5d84a4c86fd800a161165c46a04388f259fdac5a3d2f63bd1274c46ea659bd20。源于现有TrainPackageSnapshotTest.snapshot默认参数，且当时SavedTrainPackageSnapshot仍无显示字段。应复制为受版本控制固定fixture后再断言，不用新writer临时生成所谓旧fixture。
