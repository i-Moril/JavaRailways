# 当前运行线默认目的地 — 2026-11-11

用户决策：“未主动设置的默认情况下，应该设置为当前运行线的终点”。

## 所有权与行为

- SimpleATO 在已有 entered actual currentRun 捕获中附带 nullable terminalStationId，唯一来源为
  该 segment.calls 最后一项的 TERMINAL。边界外、空 calls 或非 TERMINAL 末项保持未知；不扫描
  display.plannedStops 的最后一项，不把未来运行线或完整运营车次的终点作为当前目的地。
- Bukkit 投影仅在 authored values 缺少 destination key 时添加显示默认值。metadata.name
  非空字符串或有效非空 zh/en 映射优先；空／损坏 map、空名称回落 canonical stationName。
  没有有效名称时不添加。已知部分语言沿用模板 active→zh→en 的现有 fallback。
- 显式 key 无论字符串、localized object、空串、null 或非字符串类型均保留；不偷偷覆盖。
  默认目的地仅存在于 detached render projection，不修改 captured display/domain defaults，
  不新增持久化状态、配置、兼容路径或 schema version。
- 旧 scalar destination alias：显式 authored value 仍只接受 exact string；默认时 metadata.name
  字符串优先，其次 canonical stationName，再其次第一个有效 zh/en。不按 viewer locale 改值。
- currentRun JSON 同时携带 terminalStationId，最终 effective values 进入现有 contextKey；
  实际 scope 消失／inactive 清除，终点 ID／名称变化使旧生成帧失效。没有提前切换或段外延续。

## 验证边界

新增测试源码覆盖 entered range 的 current/future run 区分、缺失终点、localized metadata、
空／损坏名称、显式 override/empty/null/nonstring、输入不可变性、inactive／scope clear 及
metadata 改变 contextKey。统一 JUnit 执行继续按用户约定延后；主会话负责源码编译和部署验证。

源码及回归源码编译、主插件打包通过：`/tmp/jr-destination-default-compile.log`，BUILD SUCCESSFUL37s。首次构建发现 JsonObjectBuilder 不支持 putAll，已改为逐项 put 后复编通过。独立静态审查无阻断；线上16条运行线的末项均为明确TERMINAL（hatchobori/kasaipark/shinkiba），无需修改时刻表。未运行JUnit。
