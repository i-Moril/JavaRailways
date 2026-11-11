# Research: 开发服真实存档与每车脚本快照改造边界

- Query: 核对开发服真实格式、文件配对及每车 package snapshot 字段是否需要迁移；区分已有格式抽离问题。
- Scope: internal；仅分析主会话只读取得的本地副本，无远端操作、构建或代码修改。
- Date: 2026-10-12

## Findings

### 来源与恢复点

- 主会话取得来源：`user@192.168.1.230:/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96/plugins/JavaRailways`。
- 本地原件：`/tmp/javarailways-dev-save-6c0B2pq1`；包含 `worlds/`、`trains/`、`rails/`、`before.sha256`、`after.sha256`、`source.tar.gz`。
- 原始压缩包 SHA-256：`cdfacf2c458eabc9ff5cefdb5e7bf33c76873994edd702462004c65afd88cdc0`。
- 本次独立校验：before/after manifest 字节相同，99项本地文件内容均与 manifest SHA-256 相符。文件解析使用 Python JSON，仅结构检查。
- 主会话报告获取时服务器正在运行；未停服、触发保存、部署或写入远端。稳定字节不证明整个服务器/跨文件原子快照；本次另外检查下述 revision/epoch，仍不把它当成停服备份。
- SSH agent socket 由宿主映射；沙盒外环境会丢失 `SSH_AUTH_SOCK`，主会话用 `SSH_AUTH_SOCK=/run/host-ssh-agent.sock ssh user@192.168.1.230` 成功。无需复制私钥，不记录密钥或指纹。
- `/tmp` 副本不是持久备份；任何未来离线迁移前须另存到用户指定持久目录。不得原地改写此取证副本或远端源文件。

### 实际文件配对与数量

6个world各有 infrastructure/runtime current 与 `.bak`，共24个世界文档；另有1个 rail-render sidecar。每组以下六个revision全部相等：outer infra/runtime、Core infra/runtime、Basic infra/runtime。runtime outer/Core/Basic physicsEpoch相等。

- `2952ad2c-41ce-40bc-b83b-1b578ae7af12`：current revision `42450000` / epoch `42450065`；backup `42448800` / `42448865`。
- `48531d55-8f7b-4d23-8134-34300e91b459`：同上。
- `9a826ffd-0e87-4341-b544-9d466ed5551e`：current `42450000` / `42450064`；backup `42448800` / `42448864`。
- `6b4a8321-6116-4743-89cf-75ae3dfd24b0`：current `4800` / `4837`；backup `3600` / `3637`。
- `9bc83aa0-a554-4434-8389-7af2285366ec`：current `4800` / `4843`；backup `3600` / `3642`。
- `f9c49e70-b02c-4d3b-8f70-7ed51cdf3fb0`：current `4800` / `4845`；backup `3600` / `3644`。

所有12个runtime的 `coreSnapshotJson.trains` 均为 `[]`。Basic内 timetables/rollingStock、dispatcher.clocks/runs 均为空。

主world `2952…` current及backup均有106条rail、106份rail metadata、30个Destination、1条Line、0个standalone WayPoint；其余world这些列表为空。主world26份metadata含非null allowedDirection，无turnoutAtA/B。所有rail连接目标、Destination引用及106个sidecar railId均存在于该world rails中；这些身份引用检查不等于完整业务合法性验证。

### 来源shape与current reader差异

来源 outer infrastructure/runtime 均为 format 1，只有 `coreSnapshotJson`、`basicSnapshotJson`，缺 `simpleAtoSnapshotJson`。

- Core infrastructure为2，rail字段为 id/curveAB/nextRailAtA/B/activeTrackAtA/B；Core runtime为1，空trains。该边界版本与current相同。
- Basic infrastructure为1，含 destinations/wayPoints/lines/lineRevision/railMetadata，`metadata.operating.allowedDirection` 仍嵌在旧位置。
- Basic runtime为1，含 timetables/dispatcher/rollingStock；dispatcher内部format1。
- 当前 outer 两文档均为2且 SimpleATO 字段必填：`BukkitPlugin/.../persistence/SplitWorldSnapshot.kt:17-60,102-104`。
- 当前 Basic infrastructure2只保留 wayPoints/railMetadata/operatingDirections：`Basic/.../persistence/BasicInfrastructureSnapshot.kt:63-69,122,153-172`。
- 当前 Basic runtime3仅配对metadata，运营数据归 SimpleATO runtime1：`Basic/.../persistence/BasicRuntimeSnapshot.kt:7-11,18`；`SimpleATO/src/main/kotlin/.../persistence/SimpleATORuntimeSnapshot.kt:60-68`。

所以不能直接把此服副本放进当前程序并声称能加载。也不能仅把数字改成current或丢弃未知字段：必须搬移30个Destination、1条Line、26个方向及runtime checkpoint到正确owner，保留revision/epoch。`RailInfrastructureMetadataMigration.kt:38-91` 已有纯infra转换及current DTO严格复验，但文件编排/完整runtime转换属于既有迁移任务；本次未执行该转换器。

### 已有曲线数据问题：独立于每车JS

主world current和backup同6条rail存在一对非严格递增arc（以下index为前一个point的0-based index）；每对position相同：

- `4f79478e-5eec-4ab7-8baa-541da2ff13d7`：index0，`0 → 0`。
- `abd3842a-2e60-48a5-b0be-b919da314d0b`：index0，`0 → 0`。
- `298057e6-4e29-4127-ad08-e398b0201cac`：index0，`0 → -7.105427357601002e-15`。
- `8ff6fbce-47d8-42fe-8a69-9ea2e397bc1b`：index0，`0 → 0`。
- `7c7cf8fc-514f-48e4-8fe6-8b15418e85c4`：index3065，`609.7059197887738 → 同值`。
- `b0161eff-a341-449e-bcb8-44adaa7fefc5`：index2，`0.16050411560684097 → 同值`。

`Core/.../physics/rail/curve/PrimeCurveImpl.kt:36-41` 在materialize时明确拒绝这类输入。不能新增runtime去重兜底；离线修复须检查point完整frame/segment语义及邻接、引用arc是否保留，输出逐rail报告后再运行current codec与业务加载。此处只证明存在非法arc，未证明简单删点就是完整正确修复。

### Sidecar、模型包与拟议字段

- `2952…-rail-render.json` 根version2，templates1、rails106；根字段version/templates/rails。sidecar没有列车/脚本快照信息。
- 本地取得的文件集合没有 `model-snapshots/` 或 `train-package-snapshots/` 文件；不能推出远端任意未纳入采样目录也绝无文件。
- `trains/s100/manifest.yml` config-version2，composition=head/motor/trailer/motor_tail/head_tail；含 behavior.js/actions.yml/control.yml，car/part YAML及 `.pre-strict-schema` 原件。只检查实际声明形状，未调用完整包loader，未改写这些文件。
- `SavedCarRenderState` 当前仅 carModelName/heldScenes/modelSnapshotHash：`BukkitPlugin/.../extension/train/TrainRenderPersistence.kt:7-11`；packageSnapshotHash当前归train：同文件14-18。
- `SavedCarModelTree.kt:10-14` 只有car+parts，不含behavior/actions；`SavedTrainPackageSnapshot.kt:11-19` 才保存actions/behavior/control/carControls，schemaVersion1。应复用现有快照store，不另建JS快照schema。
- 拟议每车新增来源package快照引用，为有明确默认值的新增字段，不因开发提交升版、不添加JAR旧数据回退。**该真实副本没有任何车厢记录，因此本次新增字段需要转换的实际记录数为0**；不能把其他旧outer/Basic/曲线问题算作该字段造成。
- 对非空旧train，需离线把准确的原train来源package快照引用映射到其原cars，再保留各车引用；不能联结后统一用主编组脚本。此副本不能验证该映射，也不能验证每车状态保留。应使用明确标为本地固定fixture的测试补充，不要求修改远端或临时造车。

### 可重复的离线复制/恢复步骤（只准备，不冒称已迁移）

以下命令仅示范本地、独立目标；执行前确认原件仍在、存储空间足够。远端保持不动：

```bash
sha256sum /tmp/javarailways-dev-save-6c0B2pq1/source.tar.gz
cmp /tmp/javarailways-dev-save-6c0B2pq1/before.sha256 /tmp/javarailways-dev-save-6c0B2pq1/after.sha256
(cd /tmp/javarailways-dev-save-6c0B2pq1 && sha256sum -c before.sha256)
offline_review_dir=$(mktemp -d /tmp/javarailways-offline-review.XXXXXXXX)
tar -xzf /tmp/javarailways-dev-save-6c0B2pq1/source.tar.gz -C "$offline_review_dir"
```

此操作得到原格式的独立工作副本/恢复点，不是current格式转换。先保留压缩包+manifest到用户指定持久位置；迁移工具未来只读源、默认dry-run，将输出写到另一全新目录，不替换该工作副本。按每world相同revision选择infra/runtime，`.bak`只配对相同revision；跨world无需强行相同epoch。完整迁移后须检查current严格codec、曲线/拓扑/Destination/Line引用和业务materialization，再与原始身份数量及字段语义报告比较。

若离线输出需重做，另建新目录并从原压缩包再次解包，保留失败输出以供审计；不通过覆盖live文件“恢复”。向开发服应用/恢复需要另获授权、停止写入后的备份与单独操作，不在本次只读范围。

## Related Specs / External References

- `.trellis/spec/javarailways/shared/versioning.md`：开发服文件上的版本不证明发布/冻结基线；不猜测升版。
- `.trellis/spec/javarailways/core/persistence.md`、`curve-geometry.md`：current严格reader与曲线输入不变量。
- `.trellis/spec/javarailways/simpleato/persistence.md`：ownership extraction、pure converter与独立迁移任务边界。
- `.trellis/spec/bukkit-plugin/backend/car-behavior.md`、`interaction-render-persistence.md`：每车行为、快照及保存边界。
- 无外部文档或依赖版本查询；本研究依赖实际副本和当前源码。

## Caveats / Not Found

- 未运行current Kotlin严格codec、infra converter、完整业务materialization或Folia/客户端；Python JSON/manifest通过不得写成存档迁移成功。
- 未取得非空真实train/car扩展数据，不能验证非空旧编组快照映射或任意JS状态保存；本设计也不承诺任意JS heap持久化。
- 未修改远端、没有自动runtime compatibility、没有schema version改动。完整旧服迁移仍未完成；该缺口与每车JS代码实现分别跟踪。
