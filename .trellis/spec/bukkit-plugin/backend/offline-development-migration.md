# 旧开发服的一次性离线迁移

## 范围与工作方式

`CURRENT`的一次性离线工具，已在真实停服副本验证并应用；不代表游戏内验收完成。源码位于`scripts/offline-world-migration/`，由BukkitPlugin独立的
`offlineMigration` source set编译；不进入插件JAR，不由启动或正常reader调用。
负责已审计开发服的旧split文件转换和当前读取器验证，不实现09-24任务的未来显式端点格式、自动迁移或运行时兼容。
实际执行与验收归[Stage 2实施记录](../../../tasks/09-18-signal-addon-implementation/implement.md)。

迁移先正常停服，打包整个`plugins/JavaRailways/`及匹配旧JAR到配置目录之外的独立恢复目录，
校验归档及停服源文件清单；再从归档取得独立副本。工具默认只读dry-run；所有文件均成功转换、严格解码并真实恢复后，
才允许输出到全新目录。源文件和恢复归档始终不改写，也不生成逐文件同目录备份。

## 转换与验证

- 基础设施复用`RailInfrastructureMetadataMigration`：旧outer1/Basic1直接拆分到当前outer2/Basic2/SimpleATO1，
  Destination和Line进入SimpleATO，公共方向进入Basic。Core使用当前既有格式，不改变端点表示或版本常量。
- 运行时只支持本次审计确认的空列车、空timetable/rolling-stock、空dispatcher clocks/runs。
  旧outer1/Basic1抽离到当前outer2/Basic3/SimpleATO1；保留dispatcher其他checkpoint标量、所有revision和epoch。
  非空旧运营数据明确拒绝，不静默清空，也不假称完成通用迁移。
- 每个current及既有`.bak`各自配对同名infrastructure/runtime。缺配对、孤立runtime、版本或revision/epoch不符均失败。
  gzip按magic识别，输出保持原transport。未知字段由旧边界或当前严格codec拒绝。
- 用真实`CoreWorld.load`、Basic infrastructure/runtime restore及SimpleATO infrastructure/runtime restore验证所有候选；
  fresh隔离世界不tick、不移动列车、不授予资源。依赖服务一旦被要求修改live依赖直接失败。
- `--validate-data-directory`另外读取当前config/physics及实际train/rail包，使用现有loader，不改模型。
  此类离线加载不证明真实客户端画面或服务器启用；缺真实Paper registry必须报告为验证缺口，不能mock后声称通过。

### 受限的旧曲线重复点修复

默认遇非递增弧长失败。只有显式`--repair-redundant-curve-points`才可删除已审计的相邻冗余点：
两个点均为DISCRETE，position/normal完全相同，arc差绝对值不超过1e-12，各tangent分量差不超过1e-7。
保留前点且不删除原末点；保留第一点、末点、总弧长及全部轨道身份/引用，不重新采样或重新计算线路。
被删除点及其向量必须具有准确的已知字段集合，不能利用删点绕过未知字段校验。
每处输出rail ID、point index、原/保留arc与最大tangent分量差；其他位置/法线/线段类型或超出范围的差异一律拒绝。
这些数值只限定本次离线修复，不放宽Core构造器，也不是新的工程配置或运行时兜底。

## 命令、输出与失败边界

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PrepairRedundantCurvePoints
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationSource=/absolute/source/worlds -PrepairRedundantCurvePoints -PmigrationOutput=/absolute/new-output
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationValidateDataDirectory=/absolute/candidate/JavaRailways
./gradlew :BukkitPlugin:migrateDevelopmentWorlds -PmigrationValidateConfigDirectory=/absolute/candidate/JavaRailways
```

`migrationOutput`必须不存在、父目录已存在，且真实路径不在源目录内。准备失败不创建输出；写入中发生I/O错误时保留失败输出供审计，
再次执行另选新目录，不能把残缺输出视为可应用候选。转换前后校验所捕获的源文件字节未变。
输出只含转换后的split配对文件和报告，不是完整worlds目录。**不得直接用它替换整个worlds目录**：
先复制完整数据目录，再按报告叠加已验证的转换文件，保留sidecar、模型、脚本及其他文件。
配置转换复用已有physics脚本的纯转换/默认dry-run；本次不调用其会生成同目录`.bak`的`--apply`路径。
文件发现只针对本次已审计的普通文件输入；未将任意源symlink包装成支持能力。实际副本已核对无symlink。

候选在离线内存中完整准备，成本随捕获字节、采样点和真实业务restore访问规模增长；不进入任何tick路径。
正式回写前再次确认目标停服及源清单未变，保留外部完整归档后才安装完整候选。
不自动重启，不将已迁移数据与不支持它的旧JAR一起启动；JAR部署另获授权。

## 必需测试与验收

直接覆盖原始重复点失败、显式受限修复成功、坐标/身份/弧长/revision/epoch保留、源字节不变、fresh输出及拒绝覆盖、
不合格点/未知字段拒绝、非空旧运行数据和配对不一致拒绝。真实副本的dry-run、strict restore、数据数量及文件清单需单独记录，
不能用空fixture绿色测试代替。归档路径/哈希、修复报告、完整候选及回写后清单留在配置目录外，禁止提交用户存档或凭据到Git。
