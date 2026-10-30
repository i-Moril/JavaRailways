# 提交与部署（2026-10-30 UTC）

用户在实现验收后明确要求“提交并部署”。功能提交 `5bd003879625630fcd95c31dc34a2c74aa9deea0`，当前分支 `dev/signal-system`；未推送。本任务未使用独立PR分支，归档使用明确支持此情况的 `--skip-branch-validation`。

## 范围与门禁

仅替换 JavaRailwaysTest 的 SimpleSignals.jar，地址 user@192.168.1.230，MCSManager实例836082a48c4f48fcb1fea4e83c57dc96。JavaRailways主插件及其他插件不变；无需数据迁移。任务提交前仅stage明确的12项代码/测试/规范文件，其他五项未跟踪文件保留。

提交树相关检查再次成功：40项任务（2执行、38沿用输入未变结果），59套660项测试，0失败/错误/跳过；Java consumer、插件及根模块边界验证和diff检查通过。独立trellis-check未发现代码问题。

## 停服、恢复点与安装

在线人数查询为1（__ART1st__）；用户已授权本次部署。通过MCSManager正常stop，16:21:09世界、全局及玩家数据保存完成；管理器status0/PID0，旧PID758259退出。

外部恢复点 `/opt/mcsmanager/backups/javarailways/boundary-naming-20261030-r9k9r600`，归档 `plugins-and-data.tar.gz` 的SHA256为 `7414d44a1c487aba0b0390d2f24d0442860a73d238030acb3a057a9beb076141`。复用前次经审查部署工具，仅修改本次恢复点前缀；脚本在停服状态核对旧/新工件、归档、全量文件及插件清单，保留权限并原子替换。

安装于16:21:52完成；142项相关数据文件未变，其他插件JAR未变。SimpleSignals旧hash `4951ea121c979b42213b108de2d9d1df4d810ffa43e2d6f78a234ebe1903ef99`，新hash `4a9c795b996116397a61315c5e31260a21fddabf33ca4785b106b19214e1fb90`。

## 实际运行检查

- 16:22:36 Done，启动26.034s；管理器status3，新PID761916。
- `jr ato status world`：RUNNING。
- `simplesignals inspect world`：installed，definitionRevision76，topologyRevision1；regions7、tables7、blocks14、fixedBlockSignals8、protectionSignals4、routeIndicators0、attachedSignals0。
- 实际安装SimpleSignals hash与候选一致；JavaRailways仍为 `efd212fbe1b4b68c871f24010c523692a3ab19f3f2988f08e78ff367988dd0b3`。
- Bridge `/health` HTTP200且ok；WebUI实际sidecar与主JAR内嵌工件一致，17项HTTP资源内容逐项一致，WebUI SHA256仍为 `f4cd3ba53c9003ccfc867ec5508e8b7720019b270779f0050cdba11e7696ae81`。
- SimpleSignals及WebUI errors.log无新增。JavaRailways errors.log新增1395 bytes，仅5条启动警告（physics overload/delta clamp与既有HTTP配置提示），无新增ERROR。

恢复点与安装清单见results目录。回退前正常停服并保存最新数据，再核对恢复归档；本次只替换插件，无需回退名称存档。真实客户端F按键、名称输入和现场显示尚未代用户验收。
