# 列车包显示模板资源

## 状态、范围与正常流程

CURRENT：PackageLoader可以读取列车包根目录可选的displays.yml，加载所属包的UTF-8 XML原文和类别默认映射，供既有内容寻址来源包快照固定。正式配置合同为schemas/train-model/displays-v1.schema.json，编辑器关联models/*/displays.yml。

Bukkit配置域拥有声明、文件读取和数据校验；ModelManager拥有准备/发布；SavedTrainPackageSnapshot和TrainPackageSnapshotStore拥有快照及hash校验；独立SimpleDisplays拥有XML/JS编译和绘制。本批不发现/调用可选服务，不编译XML、不执行script、不生成实体，也不改变动画或内联部件格式。

正常流程：PackageLoader在构造LoadedTrainPackage时调用loadTrainDisplays(packageDir)，得到TrainDisplayResources；ModelManager准备资源版本时把主包displays和各车厢实际源包的displays分别传入SavedTrainPackageSnapshot.build。准备成功后才沿已有发布边界可见。缺少文件等于空显示资源；已声明但损坏的资源必须失败，不能伪装成未配置。

## 配置与引用

```yaml
config-version: 1
templates:
  destination:
    file: displays/destination.xml
defaults:
  head_led: destination
  side_led: destination
```

config-version必须为1，templates是必填的命名map（允许空），每项仅包含file；defaults是可选的category→模板名map，缺失为空。所有ID/category非空且不能含空白，默认引用必须在同一资源对象的templates中存在。类别不设enum，同一模板可供多类别复用。命名/映射不要求当前每个类别已安装屏幕，也不进行跨包查找或失败回退。

文件统一XML，逻辑宽高和script由XML承载，不接受冗余type/width/height/source配置字段。XML语法和布局是否有效需要后续SimpleDisplays编译；本加载步骤只负责文件/引用/编码，不得以成功读取宣称模板已编译或屏幕已显示。

## 路径、解码与严格校验

loadTrainDisplays先取得源包真实路径。displays.yml只在确实不存在时返回空；目录、断链、不可读或解析后超出包根的配置文件拒绝。YAML通过loadYamlMap(..., rejectDuplicateKeys=true)读取，在生成Map之前拒绝重复key；其他格式的既有调用政策保持不变。

file使用包根相对的正斜杠路径，必须以.xml结尾；拒绝绝对路径、Windows盘符前缀、反斜杠、NUL、空路径段、单点/父级段。普通POSIX段中的冒号不属于Windows盘符前缀，不额外禁止。模板必须是可读普通文件，toRealPath后的路径必须仍属于源包根；包内符号链接允许，越界、断链和目录拒绝。使用严格UTF-8读取原文，保留XML和CDATA/script实际内容，不做插值、标准化或第二份XML解析器。

错误保留cause，定位到displays.yml及模板ID/file或defaults类别；不降级成空资源。schema处理可表达的字段/类型/路径形状，不能验证文件存在、符号链接真实包含关系或动态默认引用。

## 数据与快照机制

TrainDisplayTemplate(file, source)是可序列化的不可变值，构造/JSON解码都检查路径形状。TrainDisplayResources(templates, defaults)复制并排序两个map，发布不可修改的视图，并检查ID和引用。自定义serializer只将普通payload解码后交回同一验证构造器，不能让JSON读取绕过不变量或泄漏可修改map。

LoadedTrainPackage.displays和SavedTrainPackageSnapshot.displays都明确默认空。snapshot build重新构造资源值，把来源内容与排序映射纳入既有canonical JSON/hash。默认空displays以EncodeDefault.NEVER省略，避免encodeDefaults=true改变无显示包的既有字节/hash。非空资源包括file、XML原文和defaults；改其中任一语义内容影响新hash，map插入顺序不影响hash。

ModelManager有两条必须同步的来源路径：主包packageSnapshot使用pkg.displays，各模型来源车厢的carPackages使用owner.displays。混合来源编组不能只把主包资源复制到每车。旧TrainResourceVersion及固定快照不引用磁盘路径的实时内容，后续源文件变化只影响新准备版本。

TrainPackageSnapshotStore.load重算hash时传入displays；嵌入hash即使与文件名相同，内容被篡改也拒绝并沿已有接口报告失败/null。没有新store、编译对象、File、服务引用或tick文件读取。

## 生命周期、版本和复杂度

文件I/O沿现有包准备/重载入口执行，不增加region/entity访问或每tick工作。失败沿ModelManager既有规则保留该包最后成功发布的catalog，其他有效包可以正常更新；不旁路prepare/publish边界。显示资源本身只有不可变值，无需close；后续编译模板的owner和释放由下一批设计。

新独立displays.yml从版本1开始；嵌套TrainDisplayResources不独立编号。包快照是新增默认空字段，SCHEMA_VERSION保持1，manifest保持2，不添加legacy reader或猜测发布/部署历史。无显示基线fixture固定旧内容及hash，省略默认字段是canonical身份保证，不是并行兼容模型。

加载和hash的文本处理为O(XML总长度)；排序为O(T log T + C log C)，T为模板数、C为默认映射数。所有这些工作只发生在准备/保存/读取，不属于绘制热路径；本批不引入模板编译或帧缓存。

## 必需验证与后续

- 真实PackageLoader：缺省/空配置、UTF-8及内嵌script原文、一模板多类别；未知字段、错误类型/版本、空白ID、坏本地引用及重复YAMLkey拒绝。
- 路径：绝对/Windows/反斜杠、空/点/父级段、缺失/目录/断链、配置和模板symlink越界，以及合法包内symlink；编码错误有模板文件诊断。
- 来源：实际ModelManager混合来源车厢包的displays隔离；XML/defaults改变产生新资源版本，旧版本内容保持不变；坏配置reload保留旧catalog。
- 快照：固定无显示fixture和hash不变、显示原文往返、映射顺序无关、输入/解码map不可修改、JSON坏引用/路径拒绝、store内容篡改拒绝。
- 定向/模块测试、打包及开发模块编译，并检查host包无simpledisplays/api类。离线加载验证不能代替Paper/Folia或客户端显示验收。

TARGET：模板编译缓存/样式优先级、点级与服务端覆盖、XML/JS数据输入、原生/EntityLib屏幕、可视范围和PAPI适配仍由后续批次完成。已有配置一致性调整单独排在其余功能完成后、最终验收前。
