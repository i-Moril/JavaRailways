# 列车显示刷新与运营标志

用户已批准实施，字段名称固定为 `isInService`。

## 范围
- 当前 WebUI `AuthoringRunV1.type` 的 PASSENGER/DEADHEAD 是权威运营开关。编译为必填布尔值 `isInService`，经 scriptworker、SimpleATO run segment、capture/restore、display context、Bukkit detached currentRun 投影传递。
- 不改变现有 `currentRun == null` 时 Decorations 返回 EMPTY 的行为，不改其 provider gate。
- 语义上下文或样式刷新继续失效旧 worker generation，但保留上一成功画面至新帧成功提交；EMPTY 仍明确清屏，退休/卸载仍完整清理。
- 现有 XML 普通文字改为 minecraft:default；专用几何字体保持。内屏语言每180tick、外屏每120tick切换。
- XML 用 currentRun.isInService：false 内屏无输出；外屏显示“回送 / Out of Service”，不显示运营等级徽标。
- 保留测试服当前内屏布局与动态分页、换乘、开门侧信息，更新仓库已有 XML 样例与服务器对应三份 XML。

## 验收
- PASSENGER 与 DEADHEAD 分别编译为 true/false，经过序列化/恢复仍保持值，实际当前区段投影为相同布尔值。
- host 回归覆盖新帧等待/失败保留、旧 generation 拒绝、成功替换、EMPTY 与退休清理。
- 真实 XML 引擎验证默认字体、180/120tick边界、非运营内屏EMPTY/外屏双语回送，以及正常运营内容与稳定图元key。
- 所有受影响模块检查、前端测试/build、git diff --check；精确提交本任务修改，不推送。

## 边界
不修改 SimpleDisplays、Decorations gate、客户端字体资源或无关布局。Resources 仓库没有 XML，不新建第二套所有者。不修改运行存档、添加兼容 reader/default 或迁移；现有编译数据的重新生成与部署须先列出影响取得许可。服务器 XML 更新需备份及校验，JAR 替换/重启不包含在本次自动操作中。
