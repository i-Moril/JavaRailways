---
title: 架构决策记录
slug: /architecture/decisions/
---

# 架构决策记录

Architecture Decision Record（ADR）用于记录已经做出的、会长期影响工程结构的决定。功能计划、临时排障和未决定方案仍保存在 issue 或 `ai_tasks/`，不写成 ADR。

## 状态

- `Proposed`：方案正在评审，不能当作当前约束。
- `Accepted`：已经采用。
- `Superseded`：被新 ADR 替代，但保留历史。
- `Rejected`：评估后未采用。

## 索引

- [ADR-0001：统一文档系统](0001-documentation-system.md)
- [ADR-0002：列车模型配置采用严格契约](0002-strict-model-config.md)

## 模板

```markdown
# ADR-NNNN：标题

- 状态：Proposed
- 日期：YYYY-MM-DD

## 背景
## 决策
## 后果
## 备选方案
```
