# 色块不同平面：宿主缩放/旋转顺序

用户在原版字符矩形部署后报告色块和角度完全错误。已定位到 TextModel 将安装旋转放入 Bukkit Transformation 的 rightRotation，使最终矩阵为T*S*R。SDL输出的非等比scale本来是glyph局部X/Y尺度；宿主在旋转后的坐标轴上缩放，改变了屏幕平面法向。

修复仅交换TextModel的左右旋转槽为T*R*S；普通ItemDisplay、CompactTransform全局helper及安装坐标均不改。Native/EntityLib消费同一Transformation，均收到相同修正。

JOML定向矩阵诊断 `/tmp/jr-screen-matrix-probe.log`：使用实际door_display配置的XYZ Euler(90,-67.5,90)，等价Y=-90和局部X=22.5。未修复时宽高比1.68/2.32/12对应法向误差12.333238/21.359932/56.124828度；修正后都为0。统一缩放宽高比1旧/新均0，解释了为何此前纯文字不容易暴露。

新增TextModelTransformTest回归源码，捕获真实TextModel→DisplayVisual Transformation并按客户端T*L*S*R重建矩阵。验证复合train/car/安装旋转下原版glyph四角、法向和尺寸，另验证uniform scale旧行为等价。生产/测试源码编译通过（12s），JOML诊断执行通过；按用户原约定不执行JUnit统一套件。客户端画面仍需复核。原先只检查未旋转二维墨迹边界的SDL诊断漏过宿主旋转链，是本次漏检原因。

同批保留用户要求的独立9KB显示overlay打包修正，不再把完整列车资源包作为推荐下载。资源包包含必要的屏幕模型覆盖/背板和圈点字体，放在原S100包上方；该资源变更不用于补偿错误宿主矩阵。

## 已提交部署（2026-11-10 UTC）

提交15937c7b9a16636f1b4f8f2c5a922593bc911ce5，主JAR SHA25664794c2ca40228a61879bd3dd38c66063bfa79facab5fb01f05ae4707558deab。仅替换JavaRailways主JAR，其他9个插件均保持hash；不修改模板、安装角或线上车辆参数。

18:55:29正常保存世界/玩家并停服，旧PID1030660退出。恢复点`/opt/mcsmanager/backups/javarailways/displays-20261110-twqrjru1`，187文件逐项核对，recovery.zip SHA25664a4f551a5253f2c5e062fc7fbc886ccce6f042fbb9e1cf636278ebcafcb1698。源码/测试源码编译12s、主JAR构建8s；无JUnit执行。

18:57:14 Folia启动完成（28.009s），显示API连接正常，Bridge/WebUI本机HTTP200。18:57:37事务式资源重载成功：1列车包、3轨道包；审计时此后无ERROR/Exception/失败。实际客户端画面未验收，不能将服务健康等同于视觉通过。

审计目录（本地与远端）`/tmp/jr-text-transform-deploy-20261110`包含候选、计划/安装哈希、恢复receipt和post-deployment-audit.json。无Git推送。回滚可在正常停服后单独恢复此恢复点的旧主JAR；本修正未改变持久化格式，不需回退世界数据。用户已获独立9,075字节overlay下载，本次矩阵修复无需再次更新该资源包。
