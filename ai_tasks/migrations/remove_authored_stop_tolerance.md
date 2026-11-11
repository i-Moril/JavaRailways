# 移除开发存档中的独立停车容差

当前模型的停车、路线位置匹配和站台授权容差统一引用 `AtoSpatialPolicy.POSITION_TOLERANCE_METERS`，固定为 0.3 m。站台与插件解析结果不再接收独立容差参数；外部插件需要重新编译。未发布的 SimpleATO 基础设施格式保持版本 1。

`remove_authored_stop_tolerance.py` 只移除 `destinations[].positionToleranceMeters`。支持独立 SimpleATO 基础设施快照，以及外层版本 2 基础设施文件中的 `simpleAtoSnapshotJson`。不处理运行时快照，不启动、停止或发现服务器，不在运行时兼容旧字段。

先停止存档所有者并保存，再对明确指定的文件预览：

```sh
python3 ai_tasks/migrations/remove_authored_stop_tolerance.py /absolute/path/infrastructure.json
```

审核报告中的旧值和 SHA256 后，指定一个全新且位于存档目录之外的恢复目录执行：

```sh
python3 ai_tasks/migrations/remove_authored_stop_tolerance.py /absolute/path/infrastructure.json --apply --backup-dir /absolute/recovery/fresh-directory
```

写入前保存原始字节 `source.json` 与审计报告 `manifest.json`，检查源文件未变化，再原子替换。版本、revision、其他字段保持原语义；其他嵌入的 JSON 字符串保持原内容。迁移后必须由当前 JVM reader 读入验证，才可启动服务器。

回退时停止所有者，恢复备份的 `source.json` 及对应旧程序。测试只使用临时目录；本次未迁移运行服数据。
