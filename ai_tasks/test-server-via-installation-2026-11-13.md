# 测试服 ViaVersion / ViaBackwards 安装

## 范围与来源

- 用户要求给测试服安装 ViaVersion 和 ViaBackwards，并明确不创建 Trellis 任务。
- 本地分支：`feat/passenger-movement`，安装前 HEAD `60201085643a16df3139b6919877ffb176ef8598`。通过 `/run/host-ssh-agent/agent.sock` 只读核对远端：`main=5aa13ea26280b5e8292c8c29723bc2eb52f4822a`，已包含于当前 HEAD；远端没有同名功能分支或 `dev`。没有推送。
- 目标：`user@192.168.1.230`，MCSManager `JavaRailwaysTest`，实例 `836082a48c4f48fcb1fea4e83c57dc96`；Folia `26.1.2-8` / Java 25。
- 原 ViaVersion：`5.12.0-SNAPSHOT`；原先没有 ViaBackwards。安装官方稳定版 ViaVersion `5.12.2` 和 ViaBackwards `5.12.2`，两者均声明支持 Folia 和服务端 `26.1.2`。
- 官方来源：[ViaVersion](https://modrinth.com/plugin/viaversion/version/Fkfcr89K)、[ViaBackwards](https://modrinth.com/plugin/viabackwards/version/WFO37e8T)。下载文件与官方 Modrinth SHA512、SHA1 和长度一致；ZIP 完整性、插件名称、版本、Folia 声明已检查。
- 已安装 SHA256：
  - `ViaVersion-5.12.2.jar`：`c0b63badcb0f7d38f4443e06820f2b8142dd4f0d3b0511f9330ff1050430e6b2`
  - `ViaBackwards-5.12.2.jar`：`e989440e0aad7e12d4685d7a57df854691b67660966a95ec0450e20ce98e87ab`

## 停服、恢复点与安装

- 安装前 `list` 确认在线玩家 0；通过 MCSManager 正常 stop，15:35:06 UTC 日志确认全部世界、全局数据和玩家数据保存完成。旧 PID `1081210` 退出，manager status/PID 均为 0；未强杀。
- 恢复目录：`/opt/mcsmanager/backups/javarailways/via-install-20261113-local`。
- 完整归档：`instance.tar`，`1120440320` bytes；SHA256 `f858b0d31d21e2792caeff98193802e86d485eae49b2ccd6f2f13bc62d855c01`。`tar --compare` 和 `sha256sum -c instance.tar.sha256` 均通过。
- 外部恢复目录另存旧 `ViaVersion-5.12.0-SNAPSHOT.jar`、来源/校验 manifest、安装前插件 SHA256、停服/启动日志和安装结果。
- 仅替换 ViaVersion、增加 ViaBackwards；其余 12 个插件 JAR 的名称和 SHA256 均与安装前一致，包括 `JavaRailways.jar` 的 `9f6969570a0e0f01464cbe4b4feea790b5f0e299b2191a2a7963c2a438fa87da`。
- 通过 MCSManager 正常 open 完整启动；未通过插件管理器热加载。

## 验证

- 15:36:53 UTC 达到 `Done (29.662s)`；MCSManager status `3`，新 PID `1103524`。
- 启动日志确认 ViaVersion 和 ViaBackwards 均 `Enabling ... v5.12.2`；ViaVersion 检测到服务端 `26.1-26.1.2 (775)` 并完成映射加载。
- 控制台实际响应确认 `version ViaVersion`、`version ViaBackwards` 和 `viaversion` 均显示 `5.12.2`；`jr help` 正常，`jr ato status world` 返回 `ATO world: RUNNING`。
- Bridge `127.0.0.1:8765/health` 返回 HTTP 200 / `{"ok":true}`，WebUI `127.0.0.1:29002/health` 返回 HTTP 200 / `{"status":"ok"}`。
- 两个 Via 插件无加载/运行错误，重启后未见 `Physics stalled`，安装后的全部插件 JAR 校验和再次匹配。
- ViaVersion 有一条原版 `1.20.5–1.21.4` 协议可被利用的安全提示；记录原文于 `validation.json`，未擅自改动现有 `block-versions`。最初验证把所有 Via WARN 作为失败，审查该提示后，将提示与加载错误分开判断；保留 `validation-initial-warning-gate.json`，没有把提示描述为不存在。
- 启动日志另有 Terra `sun.misc.Unsafe` 反射 ERROR 和 TruffleAttach 加载 WARNING（通过 STDERR 标成 ERROR）。TruffleAttach 提示在安装前归档的日志中已有 6 次；Terra 问题在既有 `.local/test-server.md` 中已有记录。本次未修改这些插件或 Java 配置。
- 证据保留在恢复目录的 `release-manifest.json`、`preflight.json`、`installation.json`、`startup-and-verification.log`、`health.json`、`validation.json`。
- 未进行实际不同版本客户端登录、渲染或列车移动验收；本次没有产品源码变更，不运行仓库模块测试。

## 回滚

通过 MCSManager 正常 stop；从外部恢复目录取回旧 ViaVersion JAR，移出本次两个新 JAR，并按需从 `instance.tar` 恢复 `plugins/ViaVersion` 配置、移出新增的 `plugins/ViaBackwards` 及两个插件的重映射缓存。完整归档保留了安装前整个实例。核对旧 ViaVersion SHA256 `cc23a414b2bded80a72646a5fe43cc6b2ba97df516147a3630f402d9695e945b` 后，再通过 MCSManager open 启动。

没有修改产品源码或持久化格式；原有乘客移动相关未提交改动保留。本次只提交这份安装记录。
