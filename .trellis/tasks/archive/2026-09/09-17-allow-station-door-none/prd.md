# Allow station berth without door operations

## Goal

允许 station berth 使用 `DoorSide.NONE` 表示停站但不执行开关门操作，并把经过验证的插件部署到用户指定的 MCSM 测试服，同时留下不进入 Git 的本地运维说明。

## Background

- `StationBerth`、时刻表覆盖值和 Dispatcher 已支持 `DoorSide.NONE`；Dispatcher 会跳过实体开关门 operation。
- `DestinationRole.StationBerthRole` 仍有一条与上述语义冲突的 `doorSide != NONE` 构造校验。
- RailObjectWand 已向用户提供 `NONE` 选项，因此当前选择该值会在保存时失败。
- `TrainOperation.OpenDoors` / `CloseDoors` 必须继续拒绝 `NONE`，因为它们表示实际物理操作。

## Requirements

- 删除 `DestinationRole.StationBerthRole` 对 `DoorSide.NONE` 的禁止，不增加兼容分支或 schema version。
- 增加回归测试，覆盖带 `DoorSide.NONE` 的 station destination 可注册，并产生保留 `NONE` 的 station berth。
- 在根 `.gitignore` 中显式加入 `.local/`；在 `.local/` 内记录 `192.168.1.230`、SSH/MCSM 实例识别结果、部署路径/方式和必要验证命令，不记录口令或 token。
- 通过 SSH 构建并部署 BukkitPlugin JAR 到已确认的 MCSM 测试服实例，然后重启该实例。
- 部署后确认实例恢复运行，且启动日志没有 JavaRailways 加载错误。
- 提交本任务的相关 tracked 变更，不纳入工作区原有的 `TODO.md` 和 `models/` 改动，也不提交 `.local/` 内容。

## Acceptance Criteria

- [x] `StationBerthRole(..., doorSide = DoorSide.NONE)` 构造和注册成功。
- [x] 注册后派生的 `StationBerth.doorSide` 为 `DoorSide.NONE`。
- [x] `TrainOperation.OpenDoors/CloseDoors(DoorSide.NONE)` 的既有禁止保持不变。
- [x] `./gradlew :Basic:test` 与 `./gradlew :BukkitPlugin:shadowJar` 通过，`git diff --check` 通过。
- [x] `.local/` 被显式忽略，本地测试服说明存在且不含凭据。
- [x] `192.168.1.230` 上目标 MCSM 测试服使用新 JAR 重启成功，部署产物可由构建哈希核对。
- [x] Git 提交只包含本任务应跟踪的文件，原有用户改动保持未暂存。

## Out of Scope

- 不改变实体开门或关门 operation 的参数约束。
- 不修改持久化格式、版本或旧数据兼容路径。
- 不部署到生产服，也不改 MCSM 全局配置。
- 不自动创建游戏内 station fixture；运行态验收限于插件成功加载，具体玩法验证可在测试服内继续进行。

## Deployment Constraint

SSH 已获授权，且目标主机已在 `known_hosts` 中。只有在确认登录身份、MCSM 目标实例、插件目录和当前运行状态后才上传及重启；覆盖前保留现有 JAR 的可恢复副本，若新实例启动失败则恢复旧 JAR 并重新启动。
