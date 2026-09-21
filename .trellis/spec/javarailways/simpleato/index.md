# SimpleATO 可执行契约索引

## 范围与加载条件

本层描述处于迁移期的 `SimpleATO/` Gradle 模块。模块已经建立，当前只拥有手动导航命令的严格解析、临时时刻表生成和时刻表 JSON 展示；ATO controller、运行方向、Destination、Station、Depot、Line、Timetable model/persistence、Dispatcher 与运营寻路仍位于 `Basic/`。因此本层状态是 `TRANSITIONAL`，不能据此声称运行时已经完成解耦。新增或迁移 ATO、运行方向、运营对象、时刻表、运营寻路、`AuthoritySection`消费、信号/闭塞运用规则或列车限制仲裁时，必须加载本层。固定闭塞与保护区域定义及其安全校验由 Basic 拥有，不属于本层。

SimpleATO 只拥有平台无关的运营模型与决策。物理铁路图和列车动力学由 Core 拥有；固定闭塞/保护区域定义、authority partition、资源身份校验、物理占用、原子 claim、道岔执行与尾部释放由 Basic 拥有；Bukkit 命令、RailObjectWand、信号模型渲染和 JS/Java 扩展加载由目标 `SimpleATOBukkit` 拥有。RailObjectWand通过Basic API编辑sections，不因此取得领域ownership。

## 主题导航

| 文档 | 何时加载 | 唯一归属 |
| --- | --- | --- |
| [运营选路与行车授权](./operational-routing-and-authority.md) | 改 mission、运营 overlay、冻结路线、资源准入调用或约束合成 | SimpleATO 选路、准入客户端、单一列车保护管线 |

后续为信号脚本、运营对象与持久化分别建立 owner spec；在对应接口冻结前，不把概念草图写成已存在 API。

## 迁移期规则

- 当前仍位于 `Basic/` 的实现继续遵守 [Basic 规范](../basic/index.md)，但不得据此新增长期属于 Basic 的运营 ownership。
- 每一批迁移同时移动 contract、实现、测试与 composition；不得让 Basic 和 SimpleATO 同时成为同一运行状态的可写 owner。
- SimpleATO 只提交自己选择的 topology-bound frozen route，不提交第二份资源清单。Basic 的验证与 claim 合同见[保护区域与进路准入](../basic/protected-route-admission.md)。

当前第一批 owner slice 使用 `work.art1st.javarailways.simpleato.timetable`：

- `ManualNavigationParser` 保持严格 token/JSON 语义；
- `ManualNavigationTimetableFactory` 仍暂时生成 Basic 的 `Timetable` model，并通过 Basic destination API 读取站台角色；
- `TimetableDumper` 仍按原 shape 展示 Basic timetable；
- Bukkit command 是该 slice 的 composition consumer，Basic production 不反向依赖 SimpleATO。

这是单向过渡依赖，不是最终模块闭包。后续迁移 timetable model、Dispatcher 与 controller 时应继续删除旧 owner，而不是增加 Basic→SimpleATO bridge。

## 质量检查

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test
./gradlew :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava
python3 ./.trellis/scripts/get_context.py --mode packages
git diff --check
```

`SimpleATO` 当前没有冻结的 public API package，因此暂不加入根 Dokka/Javadoc publication。建立稳定 API 后再按明确 package 边界公开，不能把迁移期内部 package 意外变成兼容承诺。
