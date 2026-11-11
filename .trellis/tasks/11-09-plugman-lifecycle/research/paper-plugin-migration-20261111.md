# Paper 描述文件迁移：2026-11-11

## 修改与边界

用户确认目标为当前最新分支 `codex/deploy-folia-gentle-unload`，基线 `8e40af9d`。主插件通过 ResourceFactory Paper convention 生成唯一 `paper-plugin.yml`；现代 Cloud 与 GentleUnload 实现不变。外部 PacketEvents 为必需依赖，PlugManX 可选，均 BEFORE/join-classpath=true；SimpleDisplays 继续归 Decorations。原有产物边界检查同步描述文件名。

先前误落 `/workspace/minecraft/JavaRailways` 的三个文件改动已逐一匹配后撤回，其原有18个未提交文件全部保持相同SHA；未暂存或提交。

契约见 `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md` 的 Paper 描述文件与命令恢复。不能将创建新 manager、插件启用或同步现有命令等同于 COMMANDS 事件已触发。

## 构建

`:BukkitPlugin:shadowJar :SimpleSignals:jar :Decorations:jar :TunnelBridgeBuilder:shadowJar verifySignalModuleBoundaries` 通过，没有新增或运行单元测试/全仓测试。

离线构建使用 `/tmp/jr-paper-cached-plugin.init.gradle` 将 Paper convention 插件 marker 映射到已缓存的同版 ResourceFactory；SimpleDisplays API 使用已准备的 `/tmp/javarailways-simpledisplays-native-rect-maven`。最初两个旧API仓库缺少当前 DisplayContext/scale 接口；切换至匹配API后通过，未改动API或业务代码。最终构建日志 `/tmp/jr-paper-latest-build.log`。

主JAR SHA256：`86d90bcb4d2352d5f5e5e065ab74b95fb284c296c88187032d4b42ba8f8f0b86`。仅有 paper-plugin.yml，保留 main、api-version、folia-supported、管理员权限，无宿主 SimpleDisplays 依赖。

## 实际 Folia 验证

隔离目录：测试服务器 `/tmp/jr-paper-descriptor-smoke-20261111`；仅监听回环地址25587，使用独立新世界和复制的运行库，不修改在线实例。Folia26.1.2 build8、Java25、PlugManX3.2.1、PacketEvents2.14.0，保留 `threaded-regions.threads=-1`（实际单worker）。WebUI与bridge在测试配置中关闭，未验证它们的重载。

当前构建的JavaRailways、SimpleSignals、TBB，以及与当前构建SHA一致的Decorations和线上SimpleDisplays副本共存。12:23:36全部七插件启用，执行 `jr help` 返回 `=== JavaRailways 命令帮助 ===`。12:23:37首次 `plugman reload JavaRailways` 被GentleUnload暂缓，12:23:38准备完成。12:23:39第二次相同请求触发新实例启用，12:23:40PlugManX报告 `JavaRailways has been reloaded.`，12:23:42再次执行 `jr help` 返回相同帮助标题。重载后插件列表含JavaRailways、PlugManX、Decorations、packetevents、SimpleDisplays、SimpleSignals、TunnelBridgeBuilder，全部启用。

隔离实例随后正常stop，退出码0；确认PID1051603消失、25587端口关闭。完整证据 `console-latest.log`、`report-latest.json` 保留在上述测试目录。

在线主JAR检查仍为SHA256 `756d113b6c3b811f0e77f8b04772fa87e7ddc9ba4df531985158992dbed3b3ff`，未替换。

## 限制与旧基线证据

真实客户端补全/命令颜色、在线玩家、已有列车或跨区域实体清理、WebUI和其他Paper/Folia版本未验收，不能从控制台帮助成功推导。旧b14主工作区的首次隔离启动曾因宿主仍依赖SimpleDisplays造成SimpleSignals Kotlin类身份冲突；该基线不作为最新分支验收，也未因此扩展业务修改。最新分支保留全部七插件的上述流程通过。

实现与检查子代理对最终窄范围变更未发现问题；`git diff --check`通过。按用户本轮范围保留未提交修改，不自动部署在线实例。
