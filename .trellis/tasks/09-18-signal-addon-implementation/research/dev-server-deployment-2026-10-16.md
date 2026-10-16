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

## 编辑标记与工具交互修正部署（07:45–07:47 UTC）

用户在四笔交互修正提交与999项离线测试通过后明确批准部署。源码批次`40a62c1d`、`44c510bb`、`1e569162`、`66cc3314`，候选身份归[离线验证](editor-marker-ui-validation-2026-10-16.md)。部署前核对目标实例和现有两个JAR与上一已审计版本相同；有一个客户端连接，先通过控制台发10秒正常重启提示。MCSManager正常停服，07:45:37世界和玩家保存完成，旧主服498138及WebUI498336退出，目标端口停止监听。

外部恢复点：`/opt/mcsmanager/backups/javarailways/editor-ui-20261016-A5ezO4F8/configs-and-installed-jars.tar.gz`，SHA256 `edc1206f0234cd3c0017af9b2e5762d259036e6de41f895463c15f4c70b4de70`。整包保存JavaRailways/、SimpleSignals/及两个原JAR，保留ACL/xattr；父目录0700、归档0600，tar目录可读性校验通过。同目录`configs.before.sha256`记录122个文件，备份后及替换JAR后、启动前两次逐文件校验均一致；没有回退玩家新增区间或旧存档，没有生成配置同目录逐文件备份。重启后的正常运行保存不要求仍匹配这份停服清单。

安装前后均核验实际字节：

- JavaRailways.jar：`8770d68dc6f5d7d57edcea5f1e944546b0da41923e0217e5c71cc462f37a2f36`。
- SimpleSignals.jar：`4135e84020261180ebb65c85be6da44fbd882fa22ccc78fc6a747f0f4930c723`。
- 独立客户端编辑ZIP：`44540a04470c96956f7dff459fd602afee2e5f83919d43e6d813054a91e6de75`；远端留存`/opt/mcsmanager/backups/javarailways/editor-ui-20261016-A5ezO4F8/JavaRailways-editor-markers-26.1.2.zip`，没有覆盖现有综合资源包或添加自动托管/下载配置。

MCSManager正常启动，07:46:55日志`Done (25.819s)!`。JavaRailways/RailWand/RailObjectWand及SimpleSignals启用，模型包1train/3rail；SimpleSignals从primary加载玩家已有定义。07:47:23只读命令确认ATO world RUNNING，主世界installed、definitionRevision3/topologyRevision1、regions0/tables0/blocks2/fixedBlockSignals1，其余设备0。bridge `/health`返回ok，WebUI首页及引用JS均HTTP200。检查的新启动日志未见LinkageError/NoClassDefFoundError；没有借此宣称玩家字体、选择/清理或JS联动已通过。

保留未扩修告警：Terra仍报sun.misc.Unsafe反射字段缺失但随后完成加载；本次启动有一次187.8ms physics epoch overload及epoch33的234.3→200ms步长截断，亦有既有离线模式/HTTP/JDK等警告。未做性能根因诊断或负载测试，不将正常启动概括为无任何错误。未影响另一模组服、改变认证/API key、创建测试对象、应用时刻表或执行数据迁移。下一步由玩家安装独立资源包后实测显示/交互，Stage 2继续in_progress。

## 区间连续细线显示部署（08:43–08:45 UTC）

用户批准部署`e87444bb`。仅部署已有构建的SimpleSignals JAR，不重跑测试、不重建、不改主插件或世界定义；核对两份产品源码与隔离候选一致。向玩家提示10秒后重启，经MCSManager正常停服，08:43:55所有世界和玩家保存完成，实例status0且旧PID499447退出。

外部恢复点`/opt/mcsmanager/backups/javarailways/section-centerline-20261016-UipEB3ZZ/configs-and-installed-jars.tar.gz`，SHA256 `e5f43385d0beeb44bd8768ea033098ea9d5611de1ef0128ed6293596799023af`。完整打包JavaRailways/、SimpleSignals/和原两JAR，保留ACL/xattr，目录0700、归档0600，tar可读性检查通过；122个配置/存档文件在备份后和替换后、启动前逐文件校验一致。没有配置同目录备份或回退玩家数据。

实际产物SHA256：主JAR仍为`8770d68dc6f5d7d57edcea5f1e944546b0da41923e0217e5c71cc462f37a2f36`；Signals为`6780bf046b2f8ae09e0a7a3181e94f5684cebd7ab9790b282b77a9e12008d6b6`。配套客户端编辑ZIP为`f3ff10250c1f26a99ed38d86f40b059ea36b068bb6b175281d7da435f9b97dff`，留存同一外部恢复目录的`JavaRailways-editor-markers-26.1.2.zip`，本地交付路径不变；服务器不会自动更新玩家客户端资源包。

经MCSManager正常启动，08:45:08 `Done (26.564s)!`；bridge健康`ok:true`，WebUI HTTP200。启动日志未见LinkageError/NoClassDefFoundError。仍有既有Terra Unsafe反射错误，启动时一次248.1ms physics overload及epoch33的287.7→200ms步长截断，未扩修。未进行游戏对象操作或性能/显示测试；玩家须替换客户端编辑资源包后验收连续细线及分界点，Stage 2仍in_progress。

## 工具中英文与F切模式部署（09:21 UTC起）

用户批准部署`81621f64`和`fcc839d7`。复用已定向验证的隔离产物，部署前两个模块src逐目录diff一致，不重跑测试/重建。现服JAR与上一部署记录一致，向玩家提示10秒后正常重启，经MCSManager停服，09:21:22全部世界与玩家数据保存完成，实例status0、旧PID500936退出。

外部恢复点：`/opt/mcsmanager/backups/javarailways/wand-i18n-20261016-WMYXhKcL/configs-and-installed-jars.tar.gz`，SHA256 `5710a54ac43a5f4e99bbe00e547454a2afbce67e6fcdc7c881a3f0101149f612`。完整打包JavaRailways/、SimpleSignals/及两个旧JAR，保留ACL/xattr，目录0700/归档0600；tar可读性检查通过，122个配置/存档文件在备份后和替换JAR后、启动前校验一致。未在配置目录另建逐文件备份，未迁移或回退玩家数据。

本次同时更新主插件和SimpleSignals，因为Signals依赖宿主新公开的WandModeSwitch。实际部署SHA256：JavaRailways.jar `2c94760183c8acb73b50b1d6a7db4d37d784876b8734483f0081a958c3929f0b`；SimpleSignals.jar `3d443f288044087fceb2b2b0bd5e174ae65b0b310c704044b6d876d058eb1640`。客户端资源包不变；没有修改资源包托管、认证/API key、其他实例或现有业务定义。启动及客户端验收结果另续。

### 首次启动包选择错误与纠正

09:22:00主插件报缺少plugin.yml，SimpleSignals/TunnelBridgeBuilder因依赖缺失未加载；09:22:18虽然Minecraft报告Done，但bridge/WebUI不可用，不能算部署成功。原因是本次错误选取普通`JavaRailways-1.0-SNAPSHOT.jar`而非已由模块门禁验证的`shadowJar.archiveFile`。普通包hash即上段的`2c947601...`，不是可运行主包；离线门禁检查的是正确shadow产物，但部署选取与报告未绑定该任务输出。

已向用户报告；检查完整`JavaRailways-1.0-SNAPSHOT-all.jar`确有plugin.yml、WandModeSwitch.class和完整嵌入WebUI，完整主包hash `6964a9e72537a97fe34cdd1dd45e9ac2905b942b39778317d3098a9e2c535ad4`。不重新编译或加测试。再次提示10秒后正常停服，09:23:29保存完成，旧PID502021退出。在同一外部恢复目录新增完整`pre-correction-configs-and-jars.tar.gz`，hash `399b7435f3a02ecffade972e54b69f861fc299c476f41157877e22aff5d8c859`；122个配置/存档文件与最初停服清单仍逐字一致，纠正前后再次校验通过，仅替换主JAR，未回退任何存档。后续部署必须取shadow任务的完整归档，传输前同时核对descriptor和所需新API，不能由相近文件名或“已通过打包门禁”推断手选文件可运行。

纠正后09:24:21 `Done (24.789s)!`，主插件与SimpleSignals正常启用，bridge返回`ok:true`、WebUI HTTP200，当前启动日志未见上述加载错误/LinkageError/NoSuchMethodError。仍有既有Terra Unsafe反射错误、启动一次210.7ms physics overload和epoch34的226.2→200ms步长截断，未扩修。没有创建游戏测试对象、运行自动化测试或验收客户端F/语言效果。Stage 2继续in_progress，客户端沿用上一版编辑资源包即可。
