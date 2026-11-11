# 显示模板加载与来源包快照验证 — 2026-11-09

## 范围与来源

实现位于专用worktree `/home/user/.codex/worktrees/ddf5/JavaRailways`，分支 `codex/simpledisplays-train-integration`，本批基线 `aa12173d`。独立SimpleDisplays保持 `410af2e`，没有修改其源码或API依赖。配置一致性子任务 `11-09-model-config-schema-consistency` 仍为planning，已有内联版本和动画格式不变。

本批新增严格displays.yml加载、包内UTF-8 XML原文及defaults资源、不可变来源快照和hash覆盖；复用PackageLoader/ModelManager/TrainPackageSnapshotStore。没有XML/JS编译执行、样式优先级、实体渲染、PAPI或视距命令。内部合同见维护规范package-displays.md；schema已归入正式目录，任务内旧完整草案已删除。

## 定向测试与独立审查

```sh
./gradlew :BukkitPlugin:test \
  --tests '*PackageLoaderDisplaysTest' \
  --tests '*TrainPackageSnapshotTest' \
  --tests '*ModelManagerReloadTest' \
  --offline -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven \
  --max-workers=2 -I /tmp/simpledisplays-phase1-test-memory.gradle
```

结果：26项，0失败/错误/跳过（加载6、包快照7、ModelManager重载13）。日志：`/tmp/javarailways-display-loading-tests.log`。独立检查复核结果并审查生产代码、schema、编辑器关联、文档和当前合同，没有剩余阻塞问题。

覆盖实际PackageLoader/ModelManager/Store，包含无配置/空配置、带script/非ASCII/换行的XML原文、一模板多类别、未知键/类型/版本/重复YAML键、坏引用、包内及越界/断链符号链接、绝对/Windows/反斜杠/点段路径、目录/缺失文件和UTF-8编码诊断。JSON构造/解码维持不可变map与引用不变量；来源车厢包隔离、XML更新与失败重载保留旧catalog均有真实路径测试。

固定fixture `BukkitPlugin/src/test/resources/model/package-without-displays.json` 在源码修改前从原有编译基线捕获，来源为旧TrainPackageSnapshotTest.snapshot默认参数，逐字节复制并固定hash `5d84a4c86fd800a161165c46a04388f259fdac5a3d2f63bd1274c46ea659bd20`。新reader/rehash通过，证明无显示资源不会改变既有canonical身份。非空displays的源码、file、defaults变动影响hash；map顺序不影响；Store篡改被拒绝。已有版本保持：manifest2、包snapshot1；新增独立displays.yml为1，无legacy reader或对发布历史的推测。

## 正式schema验证

使用临时 `/tmp/jr-displays-schema-validator` 中的jsonschema/PyYAML，通过Draft2020-12元schema检查、4个正例和28个反例。脚本：`/tmp/simpledisplays-package-schema-check.py`。同时7份官方模型schema全部通过元schema检查，编辑器引用文件均存在。

新schema覆盖ID无空白、本地声明形状、未知type/尺寸、版本和路径词法。显式测试末尾换行、跨换行的点/父级与空段，防止正则 `$`/`.` 语义让编辑器与loader产生差异。POSIX段内冒号允许，Windows盘符前缀拒绝；NUL拒绝。文件存在/真实路径/引用关系仍由loader验证，schema未冒充文件系统或动态引用校验。

## 完整模块、打包与编译

```sh
./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar :BukkitPluginDev:compileKotlin \
  --offline -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven \
  --max-workers=2 -I /tmp/simpledisplays-phase1-test-memory.gradle \
  --continue --console=plain
```

日志：`/tmp/javarailways-display-loading-full.log`。完整Bukkit结果：**759项，1失败，0错误，1跳过**。唯一失败仍为 `ProgramExpressionsTest.one string parameter composes into destination station depot and display fields` 第130行，期望 `[central@1]` 实际 `[]`。阶段一和屏幕定义批次已记录完全相同的失败，本批未改表达式/编译器代码。没有对该项做范围外修复，也没有本批独立重放原始checkout；完整测试通过不予宣称。临时init脚本仅设置测试进程2GiB/单fork等资源，不修改仓库测试配置。

`--continue`允许独立打包/开发模块编译完成：shadowJar及BukkitPluginDev:compileKotlin均成功。Shadow包存在新loader/models、保留可选SimpleDisplays descriptor、无simpledisplays/api类。

产物 `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar` SHA-256：`489a053c7ba3cf3bf13dbcb399b25084f6bfd007ddcc5c14ecb19efe4236b5b2`。

`git diff --check`通过。Trellis上下文校验通过（既有两个大型spec有注入截断提示，当前新合同和维护规范均可直接读取）；无Core/Basic/SimpleATO代码改动。本批不部署、不进行Paper/Folia运行或客户端显示验收。
