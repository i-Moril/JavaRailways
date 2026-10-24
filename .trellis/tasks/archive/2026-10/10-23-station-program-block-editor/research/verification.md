# 验证记录

2026-10-23，分支 feat/station-program-blocks，基线 cee24662。

## 自动检查

- 前端192项测试通过，TypeScript与Vite生产构建通过。
- SimpleATO完整602项通过。
- Bukkit ScriptWorker与隔离仿真相关59项通过。
- WebUI31项通过，含到站定义验证及真正重建服务实例后磁盘文档相等。
- 三个后端模块 XML 汇总：692项，失败/错误/跳过均为0。
- 最终 shadowJar 包含 public/assets/index-BSS14sLx.js 和本地 Blockly media/LICENSE。
- git diff --check通过。

后端测试首次使用默认512MiB测试堆时耗尽，测试运行配置临时调整为2GiB后完整回归通过；没有为此修改仓库产品配置。

## 浏览器验证

使用 agent-browser 隔离会话，真实 Java/Javalin WebUI，存储路径 /tmp/jr-blocks-verification/drafts；认证和世界资料为本地fixture，不连接真实Minecraft服务器。

1. 实际工具条插入 WAIT_DURATION，修改为40并保存程序库；真实 station-programs HTTP返回revision1、稳定步骤ID、durationTicks40；磁盘JSON相同。
2. 实际鼠标拖开参数值积木；页面报连接错误，保存禁用。切换到时刻表再返回并重开记录，游离块和错误仍在，没有静默丢弃。
3. 最终构建 index-BSS14sLx.js：打开真实已保存时刻表中Old Town停站详情，在大画布插入25tick到站等待，关闭画布后保存。磁盘 run AP101/call AP101-2 包含完整 arrivalProgram。
4. 刷新页面后通过既有最后文档指针自动加载服务器文档，再打开Old Town到站画布，显示25tick且无无效草稿错误。确认页面脚本为最终asset。
5. 画布实际渲染、工具箱和非拖动插入可用；编译来源、条件、参数与每种步骤无损映射由自动测试覆盖，不冒充所有浏览器交互均已穷举。

## 独立检查

复用未编写被检查模块的代理审查共享画布和后端。已修复到站等待残留零速锁、arrivalSteps可变列表、非拖动参数连接、语言切换覆盖草稿服务器基线等发现。最终限定复查无必须修复项。

## 限制

未执行真实Paper/Folia游戏客户端验收、未推送、未部署。Vite报告主JS约2.07MB、gzip613KB；当前正确性门禁通过，未来可单独按需加载画布，不能声称本次已完成包体优化。到站上下文仅支持已明确的停站安全子集，完整库仍保留原有全部动作。
