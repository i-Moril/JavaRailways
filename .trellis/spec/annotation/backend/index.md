# Annotation Backend 规范

## 适用范围

适用于 `Annotation/` 的 KSP 代码生成实验。模块当前独立依赖 KSP、KotlinPoet 与 AutoService，`Core/build.gradle.kts` 中接入行仍被注释；不得把它描述为 Core/Basic 已启用的生产 contract。

当前 processor 不是可工作的范例：`PersistentByProcessor.kt` 把 `ConfigLib` 的 `PersistentBy` interface 当作 annotation FQCN，引用仓库中不存在的 `PersistentMapper`，生成模板还残留 `Foo`/`FooData` 等未解析类型，且模块没有 processor compile test。维护时必须先修复并用 compile-testing 证明完整生成链，禁止把现状复制到 production consumer。

## 开发前检查

- [ ] 确认任务明确要求维护实验 processor，而不是手写普通 persistence mapper。
- [ ] 同时阅读 `PersistentByProvider.kt` 和 `PersistentByProcessor.kt`，核对 package、FQCN 字符串与生成文件 package。
- [ ] 核对 `ConfigLib` 的 `PersistentBy` contract 以及目标 mapper generic 参数，不凭 simple name 猜类型。
- [ ] 计划 processor-level compile fixture；没有生成代码编译验证时，不扩大使用范围。

## 子文档索引

本层只保留此聚焦 index；当前证据不足以拆成通用 annotation/KSP 模板。持久化版本规则见共享[持久化政策](../../javarailways/shared/persistence.md)。

## 本地模式

- `PersistentByProvider` 通过 `@AutoService(SymbolProcessorProvider::class)` 注册 provider，并从 `SymbolProcessorEnvironment` 取得 `codeGenerator`/`logger`。
- 当前 processor 调用 `getSymbolsWithAnnotation(annotationName)` 并把生成文件 dependency 绑定 `containingFile`；修复后仍应保留 incremental dependency，但 annotation FQCN 必须先对应真实 annotation declaration。
- mapper contract 必须由仓库中的真实类型定义并可解析；解析失败通过 `KSPLogger.error` 报告到原 symbol，不能生成半有效文件。不要继续依赖当前不存在的 `PersistentMapper<Logical, Persistent, Dependency>` 字符串。
- 生成 API 应使用 KotlinPoet/KSP type 信息保持 package、nullability、generic 与 import 正确；删除当前 string template 中的 `Foo`/`FooData` 和其他未解析占位符。
- 不通过修改 FQCN 字符串掩盖 source package 不一致；provider discovery、processor package 和 generated code compile 必须一起验证。

证据：`Annotation/src/main/kotlin/work/art1st/javarailways/annotation/processor/PersistentByProvider.kt`、`Annotation/src/main/kotlin/work/art1st/javarailways/annotation/processor/PersistentByProcessor.kt`、`Annotation/build.gradle.kts`。

## 质量检查

- [ ] 运行 `./gradlew :Annotation:test :Annotation:compileKotlin`。
- [ ] processor 行为改动应增加 KSP compile-testing fixture，至少覆盖有效 mapper、无效 mapper diagnostic 与 generated source compile。
- [ ] 若启用到 consumer，运行该 consumer 的 clean compile/test，并检查 generated source，不只确认 processor JAR 构建。
- [ ] 确认没有顺带把实验模块接入 production dependency graph，除非任务明确要求。
- [ ] 运行 `git diff --check`。
