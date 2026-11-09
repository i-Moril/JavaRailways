# 模型包配置的共同表达规范

状态：TARGET，作者侧设计草案；依据用户确认的共同规则整理，尚未修改已有格式或解析器。范围是 JavaRailways 模型包及即将新增的显示模板配置，不扩展到所有插件配置或 Minecraft 资源包。显示模板声明已进入加载与快照实现批次，正式schema为 `schemas/train-model/displays-v1.schema.json`；其他既有格式调整仍按子任务排期。

## 执行安排（用户指定）

已有格式调整已拆为独立子任务 [11-09-model-config-schema-consistency](../../11-09-model-config-schema-consistency/prd.md)，在SimpleDisplays其他工作完成后、父任务最终综合验收前执行。当前只沿用这些原则设计新增显示配置，不提前修改内联部件版本或动画定义格式。

## 表达同一种含义时使用同一种结构

- YAML 字段使用 kebab-case；`name` 是独立命名定义的身份，`display-name` 是展示文本。包内命名集合的 map key 是该条定义的身份，不在值中重复声明 name/id。独立文件有需要时保留 name；这与集合中的 key 是同一身份在不同容器中的表达。
- 按名字查找、顺序无业务含义的定义集合用 map。有顺序、允许重复引用或表示多次安装的内容用 list；不能为了统一外观把组合列表改成 map。
- 定义与引用分开。内联定义和独立定义复用同一个内容结构，版本、集合 key 等包装不属于内容结构。引用使用既有领域的明确字段；部件仍为 `import`，模板默认映射的值为模板名。不创建根据任意字符串猜资源种类的通用 resolver。
- `offset`、`rotation`、向量分量、共同字段的缺省值和单位复用已有合同。相同形状但语义/默认值不同的对象不强行合并，例如位置向量默认0、缩放向量默认1；米制安装尺寸与 XML 逻辑尺寸也不是同一字段。
- 独立配置文档声明 config-version，嵌套对象继承所属边界版本。已有版本不强行统一数字，不因实现批次升版，不为 XML 中的 script 增加版本。
- 严格拒绝未知字段、错误类型、非法数值和歧义引用；默认值由解析器实施，schema 的 default 只是说明。YAML 重复 key 必须在映射构造前拒绝，不能先覆盖后再校验。
- 公共内容结构由官方 schemas/train-model 的 $defs 复用。文件入口、内联入口只包裹公共定义；不长期维护平行完整 schema。显示schema现已迁入正式目录，任务内不再保存平行完整副本。

## 用现有配置验证这些规则

**部件和安装引用。** 现有 PartRegistry 的 body-parts/parts/wheel-parts 共用 parsePartList，使用 inline type 或 import，附加 offset/rotation。这种 list 表示安装树，允许相同部件重复安装，应该保留。独立部件有 name/type；不能给每个安装实例强加全局唯一的定义名。未来点级样式覆盖仍需要单独解决实例寻址，不在本草案把 name 当实例ID。

现有内联部件仍要求 config-version，这是继承版本规则的待调整项。本批不删除该字段；日后调整前需遵守版本/发布基线门槛，并同步官方schema、解析器、样例和快照相关fixture。不能仅因本草案出现就开始兼容两种开发格式。

**动作和动画。** actions.yml 的 actions 是按名称查找的 map，符合定义集合规则。动作中的 animations/sounds 是执行条目列表，条目里的 name/key 是目标引用，可以重复，不应改为 map。部件上的 animations 则是带 name 的定义列表；未来是否改成命名map要先核对顺序、重复名和已有消费者，不能与动作执行列表混为一谈。

**列车组合。** manifest.composition 是有序且可重复的车型引用list，保持不变。

**公共控制结构。** car/control schema 各自定义了阻力参数 a/b/c；可以复用同一个语义明确的公共定义。只在实际修改这类schema时做局部提取，不为了本轮草案全局重写。car/control 借用 PartRegistry.CONFIG_VERSION 是实现上的版本归属问题，应在相应格式改动批次解耦，不改变磁盘数字。

**资源身份。** 部件引用已有 / 命名空间规则；Minecraft sound/item/font key 的 : 属于另一套外部契约，不为表面一致而改动。显示模板首批使用所属包的本地名称，不自动搜索其他包；跨包模板共享另行设计，不能在名字解析失败时随便回退到别的包。

## 显示模板如何沿用共同规范

包根目录增加可选的 displays.yml，保持 PackageLoader 的单包加载入口。独立YAML文档初始 config-version 为1，不修改已有manifest或snapshot版本。

- templates：必填的命名定义map，可为空；key是模板身份，值只有file。既然所有模板都由XML承载，删除冗余type，逻辑width/height由XML声明；JS写在XML内。此处暂不新增YAML内联XML或外部JS加载能力。
- defaults：可选的 category → 模板名映射，缺失等于空map。类别继续使用屏幕已有的自定义category，不推断内外属性。
- 所有模板名引用先限定为同一源包中的templates key；显示配置不重定义部件的import解析语义，也不新增另一种命名空间分隔符。
- XML file相对包根目录解析，只接受XML文件。loader需验证文件可读、路径规范、真实路径包含关系（包括符号链接）以及引用存在。JSON Schema只验证字段形状和XML后缀，不能证明文件存在、路径未越界或defaults目标存在。
- 没有displays.yml代表没有模板和默认映射。没有匹配默认项的屏幕保持无内容。声明了但损坏的文件/引用应在准备阶段给出包名、模板名和文件位置，不能静默当作未声明。

读取后的模板源码和defaults需要纳入既有来源包快照与内容hash，不能仅存路径，也不能只使用整个编组主包的样式覆盖其他来源车厢。准备/重载仍复用ModelManager既有边界；编译缓存的发布、旧模板关闭及固定快照行为需在实现批次具体化。

显示模板编译/执行由SimpleDisplays拥有。缺少可选插件时列车仍可加载；文件/引用结构校验不依赖该插件，XML/JS编译需要服务存在时执行，编译结果不得越过提供者退休边界。实体、运营数据、PAPI、可视范围和命令仍是后续批次。

## 审阅和验证边界

正式schema：`schemas/train-model/displays-v1.schema.json`，示例：schema/displays.example.yml；原任务schema副本已删除。加载/快照实现依据[本批合同](package-display-loading-contract.md)。校验覆盖正常映射、空模板集合、缺失defaults、未知字段、冗余type/尺寸、错误版本、错误扩展名、空白身份和错误引用类型；文件系统及跨字段引用校验留给后续loader测试。

待实施时应验证：来源包隔离、同一XML多类别复用、带script的XML、坏引用/路径越界/重复key、无显示配置、插件缺失、模板内容改动改变快照hash、同内容不变、失败重载保留旧资源以及模板关闭。当前加载/快照的实际验证见本批验证记录；模板编译和close仍属于后续批次。

源码依据：PackageLoader.kt、PartRegistry.kt、TrainActionConfig.kt、ModelManager.kt、SavedTrainPackageSnapshot.kt 及 schemas/train-model 的 manifest/car/part/actions/control/model-common schema。
