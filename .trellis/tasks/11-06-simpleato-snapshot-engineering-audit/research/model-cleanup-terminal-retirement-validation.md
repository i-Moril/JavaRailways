# F10 model cleanup terminal-retirement validation

日期：2026-11-07。状态：代码与离线验证完成；未提交、未部署，真实Folia stop/restart待授权。

## 实现边界

- `DefaultModelPresentationService`和`DefaultEditorTextService`只在宿主已禁用且`Bukkit.isStopping()`为真时terminally retire现有handle，复用原`closeOwner` cleanup future。
- 模型后端必须显式声明`supportsTerminalServerStopRetirement`；NATIVE的非持久化display与ENTITYLIB的packet/container visual已声明，未知后端默认拒绝。
- 热卸载继续要求实际删除；停机前已完成的真实失败不能被后来的stop覆盖。
- cleanup future的claim是唯一线性化点。独立check补修了“首次stop预检为false、claim前stop开始”的竞态，并删除editor rejection的重复错误记录。
- 没有增加pre-stop命令拦截、阻塞等待、第二套owner-unregister、持久化字段或兼容路径。

## 验证

- 实现后聚焦model/editor服务回归34项通过；独立check加入两项预检→claim确定性竞态后，聚焦36项通过。
- 独立check通过`:BukkitPlugin:shadowJar`、`:BukkitPlugin:compileSignalApiConsumerJava`和`git diff --check`。
- 主会话在最终代码与规范上使用3 GiB单fork测试配置重新运行`:BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava :BukkitPlugin:shadowJar`：735项，0 failure，0 error，1项既有opt-in skip，构建成功。
- 默认测试堆的首次完整运行曾被既有`PrimeCurveImpl`大fixture OOM终止；这不是断言失败，最终结果使用任务既定3 GiB测试heap取得。
- `:SimpleSignals:check verifySignalModuleBoundaries`最终通过，SimpleSignals 682项、0 failure/error/skip。首次把测试JVM额外限制为2个处理器时，同一runtime manager类出现6个并发timeout/时序失败；取消该额外处理器限制、保留3 GiB单fork后完整重跑通过，未为此修改业务代码或测试。

## 仍需运行期验收

在JavaRailwaysTest保留活动SimpleSignals固定闭塞信号、防护信号、进路表示器和编辑文字时执行正常stop/restart，确认：

1. 日志不再出现`disabled before model cleanup completed`或editor text对应错误；
2. `SimpleSignals model/editor cleanup completed`只报告成功；
3. 重启后没有上次进程保存的显示实体或客户端packet visual残留；
4. 普通热卸载/错误删除路径仍保持fail-loud。

离线测试不替代该Folia运行期验收，也不构成部署授权。
