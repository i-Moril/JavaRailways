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

## 已提交与部署

实现提交 `7f03fa3b`，2026-11-11UTC仅更新测试服主插件JavaRailways.jar，SHA256 `c4f0a1ed881b591d66928880101fbb4f92b095efb7c0725738b6d2b91249f668`。其他9个插件JAR、车包、屏幕坐标和客户端资源包均保留。独立样式ZIP仅因README合同更新重新打包，XML未改变，服务器样式不需替换。

正常停服保存后创建187文件恢复备份 `/opt/mcsmanager/backups/javarailways/displays-20261111-c5d6jk3m`，SHA256 `6038754d7403f9815e235214b92cdb46b9f9f96ff10613a51cda735eccd0330e`。安装清单与回执 `/tmp/jr-destination-default-20261111`；安装完成07:33:39UTC，启动Done07:34:13（27.843s），SimpleDisplays与Decorations正常启用。07:34:32资源重载成功1列车包/3轨道包，07:34:35ATOworldRUNNING；Bridge `/health`8765和WebUI29002本机HTTP200，安装/保留JAR哈希全部匹配。启动仍有既有TerraUnsafe错误和TruffleAttach警告；重载后未观察到新增模板失败。未运行延期的JUnit/统一验收，未确认客户端实际像素/可见性，未推送Git。
