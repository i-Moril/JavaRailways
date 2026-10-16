# 开发服配套部署与启动验证（2026-10-16 UTC）

## 范围与当前状态

用户在一次性迁移完成后批准部署配套JAR、正式四灯包并启动开发服。目标仅为`192.168.1.230`上的MCSManager实例`836082a48c4f48fcb1fea4e83c57dc96`，未停止另一模组服。本次没有创建列车、信号设备或闭塞，没有应用时刻表。Stage 2仍为in_progress，真实客户端和行车验收未完成。

源码基线`d30bc102`，隔离构建目录`/tmp/javarailways-fixed-block-authority.nsoyCU`；部署前已核对各运行时模块源码、构建文件及WebUI前端输入与工作区相同，不包含无关脏文件。前次完整备份及迁移记录见[迁移结果](dev-server-migration-2026-10-16.md)。

## 实际部署

- 主JAR SHA256：`70dd91f9a35ad9173e7e4ddf76f8550a8a11ded34c51f89b80963fd6dbd3e894`。
- SimpleSignals JAR SHA256：`3c29213563abf3060afdcab48ef89b4f42ec468cbaad75296b134d272521e99d`。
- 服务器包安装在`plugins/SimpleSignals/signal-packs/jr_four_light/`，含pack.yml、model/head.yml、logic/block.yml、logic/block.js、signal/block.yml，公开类型`jr_four_light:block`。完整包tar SHA256：`c243fc84a320838dc7293cdbc49622284f96696d9a31aebf5cf9588e9035357c`。
- 目标实际运行Folia 26.1.2-8、Java25，Minecraft监听29001；WebUI监听29002，私有桥接仅127.0.0.1:8765。

客户端资源从正式包现有资产派生临时26.1.2交付ZIP，仅pack.mcmeta改为实际服务端version.json声明的resource pack 84.0；其余9个资产与仓库相同。没有修改仓库1.21.11示例或覆盖原综合资源包。ZIP SHA256：`a5c27527ebbfada2688453fc931d792fb088b0593f88b0614ca94a8c9df4c95b`。

- 本地：`/tmp/javarailways-stage2-migration.PaOaYtl2/jr-four-light-26.1.2.zip`。
- 远端持久副本：`/opt/mcsmanager/backups/javarailways/stage2-20261016-m4gFYGDe/deployment-staging/jr-four-light-26.1.2.zip`。
- ZIP结构及四组item→model→texture引用检查通过；尚无真实客户端渲染证据。

## 首次部署发现的WebUI打包缺口与纠正

第一候选主JAR为`bab580a5db07188eafbb3f5f80eb647e4af831c94bb3ee8d78df2380916fe71f`。此前离线构建跳过了前端任务，而干净候选没有dist，导致内嵌WebUI JAR缺少public/index.html及assets。服务器与插件正常启动，但首页实际返回404。只检查Java构建成功不足以证明部署包完整。

复用本地缓存node_modules，在隔离候选执行真实`npm test`与Gradle`:WebUI:shadowJar :BukkitPlugin:shadowJar verifySignalModuleBoundaries`，仅跳过installFrontend，不跳过buildFrontend；离线、no-build-cache、2workers、JDK25、Gradle缓存保持/workspace。前端16套78项通过；tsc/Vite和两级JAR构建成功（11秒）。未修改产品代码、路由、依赖锁或配置。既有antd/React测试警告、前端大chunk及Gradle/JDK警告未扩修；本批未重跑未改动的Java后端及全仓测试，前批测试不能算本批重跑。

新WebUI JAR SHA256 `9c43d4bc92d25964867188f94fe7903dc730a546ab765e82d64f3d07da4ca125`，与主JAR内webui/JavaRailways-WebUI.jar哈希一致。public/index.html引用的JS/CSS都确实存在。

确认0人在线后，经既有MCSManager接口正常停服，确认保存完成及目标进程退出；没有强制kill。外部恢复目录`/opt/mcsmanager/backups/javarailways/stage2-20261016-m4gFYGDe/`中新增：

- `pre-webui-correction-configs.tar.gz`：完整JavaRailways和SimpleSignals配置目录，SHA256 `9f6b28331dfb05308ea2d941a48faabc81ed47bac5fafd0786279291365e8dfd`，权限0600。
- `pre-webui-correction-JavaRailways.jar`：保留第一次部署的主JAR；原旧版pre-deployment-JavaRailways.jar和迁移前整目录也未覆盖。

只替换主JAR，未重新应用迁移候选或回退启动后的存档。随后通过MCSManager正常启动。

## 最终验证（06:20 UTC）

- 启动日志`Done (26.946s)!`；JavaRailways和SimpleSignals均启用，模型包1 train/3 rail。
- HTTP桥接`/health`返回`{"ok":true}`。
- WebUI首页HTTP200；`/assets/index-Db0sQ5hp.js` HTTP200、1123462字节；`/assets/index-VsvBHlIV.css` HTTP200、40478字节，与JAR内容相符。
- 只读控制台`jr ato status world`返回RUNNING；`simplesignals inspect world`返回installed，definitionRevision=0、topologyRevision=1，所有设备/区间计数0。空定义并不证明游戏内JS规则已经执行。
- 实际进程：主服496850，WebUI497037；三项监听与目标一致。没有修改MCS认证/API key、在线模式、HTTP设置或其他实例。
- 当前日志仍有Terra的sun.misc.Unsafe反射错误，但其配置包随后加载完成；以及既有离线模式、明文HTTP、Voxy/Folia等警告。第一次启动期间出现过一次81.2ms physics epoch overload；空世界短观察不能用于性能结论。

## 下一步：玩家正式验收

客户端加载26.1.2资源ZIP，与原资源包并用时将四灯资源置于较高优先级。进服`192.168.1.230:29001`，执行`/simplesignals wand signal`，在设置选择“JR 四灯式主信号”。随后由真实线路验证区间/保护区域编辑、四灯现示、连续短区间、分支/环终止、ATO授权和停车。用户尚未完成这些步骤，不能将本次启动验证等同Stage 2完成。

## 共享运行库修正部署（06:59–07:00 UTC）

用户06:51创建闭塞报EnumEntries loader constraint violation，本地修正和离线验证归[实施批次](../implement.md#游戏验收发现共享运行库类加载冲突2026-10-16已部署重启待原操作复验)。用户明确批准备份并部署后，向现有在线客户端提示10秒后重启，使用MCSManager正常停服；06:59:04日志确认世界与玩家保存完成，旧主服/WebUI进程退出且端口停止监听。

恢复点：`/opt/mcsmanager/backups/javarailways/kotlin-loader-20261016-TDPGcyId/configs-and-installed-jars.tar.gz`，SHA256 `537fef8c07f2260e26a0fad0b7cb2bb4f95fb0b4e1df58ad72d48ffe48854019`。完整归档JavaRailways/、SimpleSignals/及两个原JAR，保留ACL/xattr；父目录0700、归档0600，tar目录可读性校验完成。没有在配置目录创建逐文件备份，也未覆盖此前恢复点。

仅替换`plugins/SimpleSignals.jar`，安装后SHA256 `b577b0a4b10e1593d2e11415beb190c10599397f996dc969af7e7fc1a28c0f44`；主JAR仍为`70dd91f9a35ad9173e7e4ddf76f8550a8a11ded34c51f89b80963fd6dbd3e894`。没有重装包、编辑配置、迁移/回退存档或修改其他实例。临时上传JAR哈希在安装前校验通过。

MCSManager正常启动，07:00:13日志`Done (26.964s)!`；JavaRailways和SimpleSignals启用，Signals的实际library日志仅为Graal闭包、不再包含私有Kotlin/serialization。bridge健康返回ok，WebUI首页HTTP200。新启动日志截至检查未见LinkageError，但还没有重试区间创建，不能据此宣称原操作已通过。远端没有unzip，描述符读取命令未执行成功；以已校验的完整JAR哈希、候选描述符和实际library日志确认部署内容，未为此安装工具。
