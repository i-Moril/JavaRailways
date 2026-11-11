# Execution

- [ ] Inventory exact parser/schema/test/example and server source boundaries, duplicate identities, order semantics.
- [ ] Implement strict inline/document wrappers and named animation maps; synchronize schemas/Kotlin fixtures, add deferred representative rejection cases.
- [ ] Implement dry-run/backup-first one-time converter and apply to local examples/distributed config ZIPs.
- [ ] Inspect deployed reader and live source packages/snapshots; prepare then narrowly migrate authorized test-room package boundary with recovery manifest.
- [ ] Compile source/test source, static schema and migration semantic inspection; no test suites/server/client acceptance now.
- [ ] Update docs/specs, report exact changed boundaries, backups and deferred acceptance. Separate commit after user approval; parent unified acceptance follows.


## Progress 2026-11-10

Implemented parser/common schema + affected Kotlin fixtures and new rejection cases. Internal animation DTO/snapshot list retained, only source shape changes. Local models (16 YAML +2ZIP) and display parts-tree example converted, semantic equivalence checked by converter. Server exact source captured:16 YAML+config.zip candidate; parsed snapshots unchanged. Candidates plus verified originals archived on server at /opt/mcsmanager/backups/javarailways/model-schema-20261110. Live file replacement pending answer to coordinated-reader deployment question; current old plugin cannot load new shape. No server restart/reload/JAR replacement performed. Compile source/test source passed4s: /tmp/jr-model-schema-compile.log. No test suites executed. See scripts/model-schema-consistency/README.md for manifests/recovery hashes. No commit/push.

2026-11-10用户授权协调部署已完成：a7dbe621主插件+当前schema的服务端S100源及config.zip已安装，jr resources reload成功。线上原车厢17.2/0.4几何保留，只更新schema与屏幕配置；恢复点displays-20261110-ocm2dm0l。详见主任务research/deployment-20261110.md。此前“live替换等待批准”状态已被本次部署取代；统一测试和客户端验收仍后置。
