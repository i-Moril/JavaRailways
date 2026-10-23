# 验收记录（2026-10-23）

## 结果
完成前两项实现。独立分支feat/error-log-webui，基线a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2，worktree /tmp/javarailways-error-log-webui。原工作区未提交更改没有复制或修改。未部署，未合并，用户已确认本地提交。

后端实际JUnit XML汇总：Diagnostics4、WebUI13、Core505、Basic390、SimpleATO376、BukkitPlugin546、SimpleSignals650、TunnelBridgeBuilder31，共2515 tests，failures/errors/skipped均为0。前端22测试文件/115 tests通过，tsc与Vite生产构建通过。git diff --check通过。

执行使用JDK25、Gradle9.5；早期并行构建出现编译内存/输出竞争，已停止重叠构建并以单worker/in-process3GiB串行验收。首轮测试揭示队列测试与轮转混用以及真实安装失败后问题未清除，均已修正后重跑。不能把早期失败日志当最终结果。

最终日志：/tmp/jr-backend-gates.log（包含通过的Core/Basic/ATO/Bukkit及初次Signal失败）、/tmp/jr-backend-final-package.log（最新WebUI/TBB和打包通过）、/tmp/jr-signals-final.log（最新650个Signal测试通过）。独立检查已复核disk reader、auth、writer队列、类加载器及问题清理锁顺序。

## 打包
WebUI public/index.html引用的2个JS/CSS资源均存在；宿主嵌入的WebUI JAR与独立产物逐字节相同。Diagnostics由宿主提供，SimpleSignals/TunnelBridgeBuilder附属JAR不重复打包该类。最终WebUI SHA256 d2835029b22d51b4f6f1ce39a1b6d4b7224dfab29b40fd0863b671f2f1ec17ee。

## 最终浏览器复验
最终产物重启后重新登录真实WebUI session，上游fixture继续返回worlds/host-logs503。无效请求JSON为400。隔离测试磁盘文件drafts/unavailable/corrupt.json故意损坏，GET该文件返回502并落盘JsonEOFException；authenticated /api/v1/logs?source=webui-errors&query=corrupt返回1条、module=WebUI且包含真实堆栈，日志页可展开，宿主来源警告可见。首轮分页/轮转具体结果见browser-acceptance.md，reader相关实现未再改变。

## 边界
不保证第三方自行吞掉的异常、JVM崩溃或永久不可写磁盘能被网页追溯。错误队列最多1024条，饱和明确计数并由后台报告；正常记录完整保留异常链，超长记录明确标记部分片段。日志仍遵循现有保留窗口。未执行在线Paper/Folia/玩家客户端验收；完整live插件资源reload→held恢复生命周期没有本地全装配回归，已有catalog/configuration和信号真实入口测试不能冒充该项现场验收。

## 提交建议
一个完整功能提交：feat(diagnostics): expose disk logs in WebUI and clarify world issues。
包含本worktree本任务的Diagnostics、日志出口、bridge/WebUI、世界问题清理、测试、规范与任务记录。没有识别到本worktree无关改动；用户已按项目3.4确认提交计划，不自动推送。
