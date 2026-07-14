---
title: YAML Schema 与严格校验
slug: /train-model/schemas
---

# YAML Schema 与严格校验

列车模型包使用 JSON Schema Draft 2020-12 描述结构，并由 Kotlin loader 做同样的严格字段和版本检查。

## Schema 文件

```text
schemas/train-model/manifest-v2.schema.json
schemas/train-model/part-v1.schema.json
schemas/train-model/car-v1.schema.json
schemas/train-model/control-v1.schema.json
schemas/train-model/actions-v1.schema.json
schemas/train-model/model-common.schema.json
```

构建后的静态站点会把这些文件复制到 `/schemas/train-model/`，可供编辑器下载。仓库的 `.vscode/settings.json` 已将 `models/*` 下不同位置的 YAML 关联到相应 Schema；需要安装 Red Hat YAML 扩展才能获得补全和即时诊断。

## 严格规则

- 未声明字段会报错，避免 `composiiton`、`postion` 等拼写错误被静默忽略。
- `manifest.yml` 当前只接受 `config-version: 2`。
- part、car、control 和 actions 当前只接受 `config-version: 1`。
- train manifest 必须有 `composition` 且不能有 `rail-config`；rail manifest 正好相反。
- `item_display` 必须提供 `item`，且 `item-model` / `custom-model-data` 至少有一个。
- 所有数值字段必须真的是有限数值；整数字段不能用小数代替。
- `actions.yml` 和其中的每个 action、animation、sound 都拒绝未知字段。

:::warning YAML 布尔值陷阱
SnakeYAML 会把未加引号的 `on` / `off` 解析为布尔值。scene 名必须写成：

```yaml
scene: "on"
scene: "off"
```
:::

## 校验命令

```bash
cd docs-site
npm run validate-models
```

该命令递归校验 `models/` 中的 manifest、control、actions、cars 和 parts。`npm run build` 与 `npm run check` 都会自动执行它。

JSON Schema 只负责单文件结构。部件 import、composition 引用、重复 scene 等跨字段或跨文件语义仍由真实 Kotlin loader 负责，因此提交前还需要运行：

```bash
./gradlew :BukkitPlugin:test
```
