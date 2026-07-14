---
title: ADR-0002：列车模型配置采用严格契约
slug: /architecture/decisions/0002-strict-model-config
---

# ADR-0002：列车模型配置采用严格契约

- 状态：Accepted
- 日期：2026-07-13

## 背景

模型 YAML 原先会忽略大部分未知字段，manifest 不拒绝未知版本，actions 也没有版本。拼写错误可能在启动时被静默忽略，手写文档与 loader 已经出现不一致。

## 决策

- manifest 只接受 `config-version: 2`。
- part、car、control 和 actions 只接受 `config-version: 1`。
- `actions.yml` 顶层新增必填 `config-version: 1`。
- 所有配置层级拒绝未知字段和错误类型。
- JSON Schema Draft 2020-12 是公开结构契约；Kotlin loader 执行等价检查并继续负责跨字段、跨文件语义。
- `models/` 中的示例必须同时通过 Schema 校验和 BukkitPlugin loader 测试。

## 后果

旧模型包若包含额外字段、错误类型或未版本化 actions，将在加载时失败并给出字段上下文。格式演进必须新增版本和迁移说明，不能在同一个版本中静默改变字段含义。
