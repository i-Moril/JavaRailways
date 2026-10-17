# Exact endpoint persistence design

## Boundary and decisions

Core负责当前完整端点表示和共享验证，Bukkit直接消费者只适配其值类型；独立offlineMigration source set拥有一次性UUID-only开发数据转换。
当前表示、端点图预校验和错误行为归[Core持久化合同](../../spec/javarailways/core/persistence.md#显式目标端点的保存和恢复)，
结构prepare/undo的身份与事务规则归[轨道拓扑合同](../../spec/javarailways/core/rail-topology-and-occupancy.md)。
保持rail-local双向记录；不另建世界连接catalog，不改变运行时TopologyState、连接容量或自动连轨策略。

## End-to-end flow

writer直接从away-view保存rail UUID+A/B，普通load在world构造前完成全图预校验，再安装disconnected rails及已验证连接。
prepared target snapshots、prepared restore、structural undo和隔离模拟全部沿用同一精确身份，消除任何中间按railId降格。
验证失败不按几何替换声明端点，不改写live目标；保留现有各owner失败清理，不扩展全world通用rollback工程。

## Offline migration and version policy

旧ownership转换已于10月16日在开发服完成，详见[最新源核对](research/endpoint-save-audit-2026-10-17.md)。
本批转换模式针对outer2/current nested owners中的UUID-only连接；未知/混合形状、歧义与业务非法性均拒绝。
转换器仅在旧输入边界依据几何唯一确定端点，完成后交给新current严格codec及真实业务restore。
工具输入、fresh-output、源字节复核、runtime字节保持和支持窗口均归[离线迁移合同](../../spec/bukkit-plugin/backend/offline-development-migration.md#已完成ownership转换后的显式端点模式)。
原ownership工具若继续保留，组合相同端点步骤后也必须产出新current，不能留下当前reader读不了的输出。

按未发布开发格式政策直接修正current，Core infrastructure仍为2，其他边界版本保持；旧开发形状只由离线工具显式字段识别，
不虚构发布代际、不新增插件启动迁移或运行时旧reader。工具不进入主JAR。

## Operational boundary and rollback

开发服唯一目标为user@192.168.1.230的已记录MCSManager实例；本地只保存源副本、候选和报告。
本批只读远端核对与离线验证。未来应用前须停写、取得完整外部归档及匹配旧JAR、重新核对数据清单，
再叠加验证后的转换文件到完整候选；禁止用仅含世界配对的输出覆盖整个worlds目录。
回退依靠完整恢复点和匹配JAR；不提供运行时down-migration，不在本批实施远端替换。
