---
id: index
slug: /
title: MineRails 文档
sidebar_position: 1
---

# MineRails 文档

MineRails 是面向 Paper 的铁路模拟插件。当前文档首先覆盖工程设计、扩展开发边界、列车模型包和 JS 时刻表。

:::warning 当前状态
项目仍使用 `1.0-SNAPSHOT`，公开 Java/Kotlin API、列车模型格式和时刻表 DSL 尚未承诺稳定兼容。页面描述的是当前源码实现；实验性能力会在页面中单独标记。
:::

## 文档入口

- [工程设计](architecture/overview.md)：模块边界、线程模型、物理 epoch 和持久化。
- [自动驾驶与运营协同设计](architecture/automatic-train-operation.md)：已实现的 ATO Phase A/B/C1/C2/C3 契约与后续设计边界。
- [ATO 实施路线图](architecture/ato-roadmap.md)：插件生命周期和性能阶段的剩余计划。
- [Phase C2 Station 待验证项目](architecture/station-validation-checklist.md)：Station 固定边界和逐项验收证据。
- [Phase C3 TrainOperationGateway 验收清单](architecture/train-operation-validation-checklist.md)：车门 ticket、失败策略和 Bukkit 适配证据。
- [扩展插件开发](extensions/overview.md)：当前公开 API 状态、Dokka Reference 和使用限制。
- [列车模型包](train_model_packages.md)：模型包 YAML、动作和 `behavior.js`。
- [JS 时刻表](timetable_js_syntax.md)：时刻表 DSL、dry-run 和当前限制。
- [性能评估](performance_assessment.md)：已知热点和优化方向。

## 文档维护原则

1. API、配置格式或 DSL 变更必须在同一个变更中更新文档和示例。
2. `ai_tasks/` 是内部计划与历史记录，不作为当前行为的依据。
3. 生成的站点和 API Reference 不是事实来源；发生冲突时以对应版本源码和测试为准，并修正文档。
4. 示例应尽量来自 `models/` 或 `examples/` 下可被自动检查的文件。
