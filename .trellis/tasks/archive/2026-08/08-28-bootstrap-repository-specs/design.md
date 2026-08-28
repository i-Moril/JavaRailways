# 技术设计：全仓库 Trellis Spec Bootstrap

## 1. package 映射

`.trellis/config.yaml` 使用以下 package：

```yaml
packages:
  javarailways:
    path: .
  core:
    path: Core
  config-lib:
    path: ConfigLib
  basic:
    path: Basic
  annotation:
    path: Annotation
  bukkit-plugin:
    path: BukkitPlugin
  bukkit-plugin-dev:
    path: BukkitPluginDev
  tunnel-bridge-builder:
    path: TunnelBridgeBuilder
  webui:
    path: WebUI
  docs-site:
    path: docs-site
    type: submodule
default_package: javarailways
```

根 package 与子项目路径存在有意重叠：`javarailways` 只承载根构建、跨模块依赖、全局质量与持久化政策；具体实现约定归属于最小子项目 package。Trellis package 配置负责上下文和 spec 路由，不改变 Gradle/Git 结构。

映射修改后，将当前 bootstrap task 的 `package` 从初始化时误设的 `docs-site` 改为 `javarailways`，再运行 package discovery 验证。

## 2. spec 结构

目标结构按真实职责生成：

```text
.trellis/spec/
├── javarailways/
│   └── shared/                 # 根构建、模块边界、持久化、全局质量
├── core/
│   └── backend/                # API、物理/runtime、persistence、测试
├── config-lib/
│   └── backend/                # 配置/serialization contract
├── basic/
│   └── backend/                # railway domain、API、timetable、persistence
├── annotation/
│   └── backend/                # 小型注解模块；聚焦 index
├── bukkit-plugin/
│   └── backend/                # lifecycle、command/tool、Folia、render、persistence
├── bukkit-plugin-dev/
│   └── backend/                # 游戏内开发测试边界
├── tunnel-bridge-builder/
│   └── backend/                # 独立插件、算法、rail service、wand
├── webui/
│   ├── backend/                # Javalin、HTTP/auth、repository/bridge、打包
│   └── frontend/               # React、state、API、recovery、i18n/theme、测试
├── docs-site/
│   └── frontend/               # Docusaurus、版本化文档、schema/model scripts、CI
└── guides/                     # 仅保留跨 package 的短思考清单
```

每个 layer 必须有 `index.md`。只有当一个主题具有独立查阅价值且有足够本地证据时才拆出 topic 文档；Annotation、ConfigLib、BukkitPluginDev 等小模块不机械复制完整模板。

## 3. 证据采集与写作边界

按 package 执行：

1. 阅读 build manifest、顶层 README/requirement 与目录结构。
2. 读取公开 API、composition root、serializer/repository、主要 service/helper。
3. 读取代表性测试，确认错误、边界和验证命令。
4. 将规则写成“适用场景 → 本地模式 → 证据路径 → 禁止模式/验证方式”。
5. 删除与该 package 无关的模板章节。

不把单个偶然实现提升为规范；重要规则至少应由权威文档、公开 contract、测试或重复模式支持。

## 4. 语言与 contract

- 正文、标题、解释和清单使用中文。
- 类名、函数名、package、路径、命令、配置 key、JSON 字段、固定 token 保留原文。
- 不翻译 serialized enum 或协议字面值。
- 语言规则写入共享 index，并在 package index 中保持一致。

## 5. 兼容与迁移

本任务不改变产品持久化格式，也不需要 runtime compatibility。对 `.trellis/spec/` 的模板重组是一次性文档迁移：

- 先验证 package 映射；
- 再写新目录；
- 最后移除已被新结构取代的模板文件；
- 用链接/索引检查防止产生悬空引用。

## 6. 回滚策略

- package 映射异常时，只回滚 `.trellis/config.yaml` 与 task package 字段，不继续 spec 写作。
- 单 package spec 不合格时，回滚或重写该 package 目录，不影响其他 package。
- 始终逐路径检查 `git status`，不得使用会覆盖现有脏工作区的 reset/checkout/clean。
