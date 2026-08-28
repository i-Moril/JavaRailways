# 跨层 contract

先画出实际数据流，例如：

```text
React types/api
  → Javalin route/session
  → Bukkit HTTP bridge
  → Basic validator/runtime
  → Core physics snapshot
  → Bukkit persistence/render
```

逐边界检查：

- [ ] 输入/输出的 method、path、field、enum token、单位、方向和 nullable/default 是否明确。
- [ ] validation 在哪个入口执行；下游是否仍验证世界引用和业务不变量。
- [ ] error 如何映射为 exception/result/HTTP status/player message，是否保留定位上下文。
- [ ] thread owner 和 state visibility 在何时切换；跨线程是否只传 immutable value/ID。
- [ ] persisted boundary、schema version、revision、physics epoch 是否被正确区分。
- [ ] breaking change 是否只升级最小边界；旧输入是否只在 migration boundary 处理。
- [ ] WebUI Java route、`api.ts`、`types.ts` 与两侧 tests 是否同步。
- [ ] docs/schema/example 是否引用同一个 source of truth，生成副本是否由 script 重建。
- [ ] submodule/独立 JAR/service 等部署边界是否有单独 build/CI/lifecycle 验证。

完成后运行所有 affected package 的 focused test；不要用一个上层 happy-path test 替代每个边界的 invalid/failure fixture。
