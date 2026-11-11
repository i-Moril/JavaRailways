# 开发服整目录备份与离线迁移结果

日期：2026-10-16。用户明确批准先正常停服，再将整个配置目录打包备份到目录外，随后迁移；不生成同目录逐文件备份。
本批没有授权/执行新版JAR部署或重启。源码基线`7f706fa5`，工具及契约随本批提交；旧09-24端点/自动迁移任务未启动。

## 停服与完整恢复点

精确目标：`user@192.168.1.230:/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`。
先核实原Folia PID55178的cwd，管理器无现成API key且旧控制台FIFO缺失；未修改认证配置。
获用户批准后发SIGTERM触发Folia正常关闭钩子，日志记录JavaRailways停用及所有世界保存，主进程和WebUI子进程55357均退出。
另一模组服PID408597不受影响，没有强杀或重启。

独立恢复目录：`/opt/mcsmanager/backups/javarailways/stage2-20261016-m4gFYGDe/`，权限0700。

- `JavaRailways-full.tar.gz`：完整`JavaRailways/`及匹配的旧`JavaRailways.jar`，约66MiB，归档权限0600，包含原权限/ownership/ACL/xattr。
- 归档SHA256：`b8516d2a138b882ad46764f460c07abb5949405163de84e1bc475b3299aaa041`。
- `before.sha256`与`after.sha256`一致，共114个数据文件；归档解包后114项校验全部通过。
- 原安装JAR SHA256：`22b0b36c7b37851de3a5c7857abf1ae7b495fc62deea67941a7afccbb97f0768`，应用后仍相同。
- 本地独立副本：`/tmp/javarailways-stage2-migration.PaOaYtl2/source/JavaRailways`；它不是唯一持久恢复点，远端完整归档才是。

## 迁移及实际保留的数据

机制、限制、命令与失败处理归[离线迁移合同](../../../spec/bukkit-plugin/backend/offline-development-migration.md)，不在此复制另一套算法。
世界原始dry-run在非递增arc准确失败；显式受限修复后，6个world的current与既有`.bak`共12对通过当前严格codec和真实Core→Basic→SimpleATO恢复。
保留106轨道、106 metadata、26方向、30 Destination及1 Line；其他world为空，全部旧runtime trains/timetables/rolling-stock/clocks/runs为空。
主world current revision/epoch为49411675/49411748，backup为49411200/49411266，原值保持不变。

6条rail各删除1个符合条件的冗余点，主文件和原有`.bak`共12次；位置/normal完全相同，最大tangent分量差1.2115094373235933e-8，
仅1处重复arc相差7.105427357601002e-15；总长、首末点与rail引用保持。详细rail/index/arc记录位于外部`migration-report.txt`。
未知字段及不合格点拒绝；独立review发现删点前可能隐藏未知字段，已在最终代码修复并加回归。

physics dry-run仅schema1→当前schema2和`track.gravity-mps2`→`track.gravity`，未改数值，最终配置与既有纯迁移函数输出逐字相同。
`config.yml`不变；extension工具对12个runtime dry-run结果为0转换，不据此改写extension。
最终完整候选仍有114个文件：24个split世界文件及physics改变，89个文件不变。原有world `.bak`被转换，不额外创建新的逐文件备份。
render sidecar、模型、脚本、webui数据及其他文件保留；没有把仅含转换配对文件的输出当作完整worlds目录覆盖。

## 验证及远端应用

- 最终隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`运行Bukkit完整测试：96套405项，failure/error/skip均0；同时执行shadowJar、Java消费者/模块边界与真实12对dry-run，17秒、35tasks（5执行/30up-to-date）。工具最终源码同步后执行，不混用上一版验证。
- final data validator另外4秒通过当前config/physics、trains/s100及3个rails包；使用真实缓存Paper registry bootstrap，无mock、无服务器启动。最初无registry的包加载失败已由真实bootstrap解决，不冒称当时已通过。
- 最终离线脚本SHA256：`0865705dc3c1f2e02a8bdf11fffaa1146d69d6bdff6fe7d2d92880f4bcfa175a`。主JAR内无`javarailways/offline`或迁移类；未改运行时代码/版本常量。
- 最终日志：`/tmp/javarailways-offline-migration-final-check.log`、`/tmp/javarailways-migration-complete-candidate-validation.log`，已复制到远端独立恢复目录；该目录同时保留候选归档及完整候选清单。
- 候选整目录归档SHA256：`b66c53e7056418dcd7f94a67b1334ab4974c64d7a3efbe208460f921b7ca6afb`；上传、解包及全部114项校验通过。
- 应用前重查所有Java进程cwd，确认目标仍停服；`pre-apply.sha256`与停服备份清单一致，旧JAR未变。
- 先从旧目录复制所有权限/ownership/属性到候选而不改内容，再把旧整目录移到外部`pre-migration-directory`，安装完整候选；失败路径预设恢复原目录，实际未触发失败恢复。
- 应用后`post-apply.sha256`与候选114项完全一致，旧JAR哈希保持不变；未启动服务器。完整归档及旧整目录均保留，可回滚，不删除任何恢复点。

## 尚未验证及下一步

远端数据迁移已完成，但**暂不可用旧JAR启动**。部署配套新版JavaRailways/SimpleSignals、安装正式信号包及匹配客户端资源，须另获授权。
本批未验证真实Folia启用、render/SimpleSignals sidecar加载、列车JS行为或客户端/行车操作。不能把配置/模型loader通过等同于游戏验收。
本工具只支持已审计的空旧运行数据；任意非空旧train/运营记录或源symlink不是当前支持能力。Stage 2与整仓审查仍in_progress。
