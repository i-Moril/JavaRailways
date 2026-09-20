# SimpleATO 可执行契约索引

## 范围与加载条件

本层描述目标 `SimpleATO/` 模块；该 Gradle 模块尚未创建，因此本层所有合同均标为 `TARGET`，不能据此声称运行时已经完成解耦。新增或迁移 ATO、运行方向、Destination、Station、Depot、Line、Timetable、运营寻路、保护区域的运营消费、闭塞区间、信号规则或列车限制仲裁时，必须加载本层。保护区域定义及安全校验由Basic拥有，不属于本层。

SimpleATO 只拥有平台无关的运营模型与决策。物理铁路图和列车动力学由 Core 拥有；保护区域定义、资源身份校验、物理占用、原子 claim、道岔执行与尾部释放由 Basic 拥有；Bukkit 命令、RailObjectWand、信号模型渲染和 JS/Java 扩展加载由目标 `SimpleATOBukkit` 拥有。RailObjectWand通过Basic API编辑保护区域，不因此取得领域ownership。

## 主题导航

| 文档 | 何时加载 | 唯一归属 |
| --- | --- | --- |
| [运营选路与行车授权](./operational-routing-and-authority.md) | 改 mission、运营 overlay、冻结路线、资源准入调用或约束合成 | SimpleATO 选路、准入客户端、单一列车保护管线 |

后续为信号脚本、运营对象与持久化分别建立 owner spec；在对应接口冻结前，不把概念草图写成已存在 API。

## 迁移期规则

- 当前实现仍位于 `Basic/` 时，继续遵守 [Basic 规范](../basic/index.md)，但不得据此新增长期属于 Basic 的运营 ownership。
- 每一批迁移同时移动 contract、实现、测试与 composition；不得让 Basic 和 SimpleATO 同时成为同一运行状态的可写 owner。
- SimpleATO 只提交自己选择的 topology-bound frozen route，不提交第二份资源清单。Basic 的验证与 claim 合同见[保护区域与进路准入](../basic/protected-route-admission.md)。

## 质量检查

```bash
./gradlew :Core:test :Basic:test
python3 ./.trellis/scripts/get_context.py --mode packages
git diff --check
```

模块创建后补充 `:SimpleATO:test`、Dokka、consumer fixture 和下游 adapter 验证；在此之前不得写入不存在的构建命令。
