# 全局质量检查

## 按范围选择命令

不要无条件用根 `build` 代替 focused test。至少运行所有 touched module 及其直接 consumer；跨模块 contract 变更再扩大到 CI 全集。

```bash
# JVM 模块 focused tests
./gradlew :Core:test
./gradlew :Basic:test
./gradlew :ConfigLib:test
./gradlew :Annotation:test
./gradlew :BukkitPlugin:test
./gradlew :BukkitPluginDev:test
./gradlew :TunnelBridgeBuilder:test
./gradlew :WebUI:test

# 平台/sidecar deployable artifacts
./gradlew :BukkitPlugin:shadowJar :TunnelBridgeBuilder:shadowJar :WebUI:shadowJar

# WebUI frontend
npm --prefix WebUI/frontend ci
npm --prefix WebUI/frontend test
npm --prefix WebUI/frontend run build

# docs-site（独立 submodule 工作树）
npm --prefix docs-site ci
npm --prefix docs-site run check
```

根 CI 的基线在 `.github/workflows/build.yml`：JDK 25、Node 24，执行各主要模块测试并构建 `BukkitPlugin`/`WebUI` shadow JAR。若本地命令与 CI 不同，review 中应说明原因；`TunnelBridgeBuilder` 的 focused test 仍应在改动该模块时显式运行。

CI 先用 JDK 21 从固定提交构建 SimpleDisplays API，发布到 runner 的临时 Maven 仓库，再通过 `simpleDisplaysRepository` 供 Decorations 编译和测试使用。源码来源、固定提交与本地准备方式见 [Decorations 构建说明](../../../../Decorations/README.md#build-and-dependencies)。

## 全局审查

- 使用 `rg` 搜索变更过的 API、serialized field/token、配置 key 和版本号的所有 producer/consumer。
- 对 `Core`/`Basic` API 改动运行 downstream 编译/测试，并确认 Dokka 公开范围。
- 对 Paper/Folia 改动检查 target-aware scheduler/ownership，必要时使用 `./gradlew :BukkitPlugin:runFolia` 做人工 smoke；自动测试通过不代表跨 region 场景已验证。
- 对 WebUI contract 同时检查 Java route、`frontend/src/api.ts`、`frontend/src/types.ts` 和相关两侧测试。
- 对 docs-site 输入检查根 `schemas/`、`examples/` 与 submodule `reference/` 的来源关系，不手改生成的 `static/` 副本。
- 最终运行 `git diff --check`；分别查看根仓库与 submodule 状态，避免混合提交。

## 禁止的验证捷径

- 只运行新 writer/new reader round-trip，不保留旧 fixture 或 invalid input test。
- 因某模块没有 production test 就跳过 compile/test task；例如 `Annotation` 和 `BukkitPluginDev` 仍应运行其 Gradle test task。
- 通过放宽 decoder、忽略异常或 fallback 到空世界来让测试“通过”。
- 在脏工作区使用 reset/clean/宽泛 checkout 整理无关修改。
