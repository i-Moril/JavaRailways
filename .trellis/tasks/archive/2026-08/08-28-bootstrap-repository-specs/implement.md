# 实施计划

## 阶段 A：修正并验证映射（必须最先完成）

- [ ] 记录当前 `.trellis/config.yaml`、spec tree、task package 与 `git status` 基线。
- [ ] 将根项目、八个 Gradle 子项目和 `docs-site` 写入 `packages`；设置 `default_package: javarailways`。
- [ ] 将当前 task 的 package 从 `docs-site` 修正为 `javarailways`。
- [ ] 运行 `python3 ./.trellis/scripts/get_context.py --mode packages`，逐项确认十个 package、路径、submodule 标记和默认 package。
- [ ] 映射未通过时停止，不进入 spec 批量写作。

## 阶段 B：建立 spec 骨架

- [ ] 按 `design.md` 创建 package/layer 目录和 `index.md`。
- [ ] 保留 `docs-site/frontend` 作为目标目录，但将 init 模板改写为真实 Docusaurus 规范。
- [ ] 列出将保留、拆分、替换和删除的模板文件，避免同时存在新旧事实来源。

## 阶段 C：根项目与 JVM 模块 bootstrap

- [ ] 写 `javarailways/shared`：Gradle 模块职责、依赖方向、跨模块构建、持久化政策和全局验证。
- [ ] 写 `core/backend`：公开 API、物理/runtime、事件/控制、persistence、testFixtures 与测试。
- [ ] 写 `basic/backend`：railway domain、API、curve/safety/route/timetable、persistence 与测试。
- [ ] 写 `config-lib/backend` 与 `annotation/backend`：只保留与实际小模块匹配的聚焦规则。
- [ ] 写 `bukkit-plugin/backend`：composition/lifecycle、Cloud command/tool/dialog、Folia thread ownership、render、extension、persistence 和测试。
- [ ] 写 `bukkit-plugin-dev/backend`：开发测试插件边界，禁止承载生产领域规则。
- [ ] 写 `tunnel-bridge-builder/backend`：独立插件依赖、algorithm/rail/wand 结构和验证命令。

## 阶段 D：WebUI 与 docs-site bootstrap

- [ ] 写 `webui/backend`：Javalin route、auth/error status、repository/bridge、frontend 打包与 Java 测试。
- [ ] 写 `webui/frontend`：React 组件/state/hook、`api.ts` contract、local recovery、i18n/theme、Vitest/build。
- [ ] 写 `docs-site/frontend`：Docusaurus 结构、多语言/版本路径、schema/model/example scripts、submodule 与 npm CI。
- [ ] 重写共享 `guides` 为短清单；删除重复或仅描述 Trellis 上游内部实现的内容。

## 阶段 E：全量验证与审查

- [ ] 验证 package discovery：

```bash
python3 ./.trellis/scripts/get_context.py --mode packages
```

- [ ] 验证 placeholder、中文正文、索引、相对链接、代码围栏、UTF-8 与行尾。
- [ ] 用 `rg` 检查八个 Gradle module 与 docs-site 都有 spec 归属，并检查关键政策未丢失。
- [ ] 逐路径审查 `git status`，确认产品运行时代码没有被 bootstrap 修改。
- [ ] 运行：

```bash
git diff --check
```

- [ ] 执行 Trellis quality check，修复 CRITICAL/WARNING；最终审查 package/spec/context 一致性。

## 风险与停止点

- **路径重叠**：根 package `.` 只承载跨模块规则；若 Trellis package discovery 输出异常，立即回滚映射。
- **模板漂移**：`trellis update` 可能把本地 spec 识别为用户修改；不得为了消除提示覆盖项目规范。
- **脏工作区**：禁止 `git reset --hard`、`git clean`、宽泛 checkout 或批量 stage。
- **证据不足**：无法由源码/测试支持的规则不写入 spec；记录为任务 notes，而不是猜测。

## 启动前关卡

- [ ] `prd.md`、`design.md`、`implement.md` 已由用户审阅。
- [ ] `implement.jsonl` 与 `check.jsonl` 均包含真实研究/政策上下文。
- [ ] 用户在最终规划摘要之后明确批准开始实施。
