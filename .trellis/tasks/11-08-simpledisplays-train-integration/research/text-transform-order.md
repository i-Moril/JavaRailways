# 色块不同平面：宿主缩放/旋转顺序

用户在原版字符矩形部署后报告色块和角度完全错误。已定位到 TextModel 将安装旋转放入 Bukkit Transformation 的 rightRotation，使最终矩阵为T*S*R。SDL输出的非等比scale本来是glyph局部X/Y尺度；宿主在旋转后的坐标轴上缩放，改变了屏幕平面法向。

修复仅交换TextModel的左右旋转槽为T*R*S；普通ItemDisplay、CompactTransform全局helper及安装坐标均不改。Native/EntityLib消费同一Transformation，均收到相同修正。

JOML定向矩阵诊断 `/tmp/jr-screen-matrix-probe.log`：使用实际door_display配置的XYZ Euler(90,-67.5,90)，等价Y=-90和局部X=22.5。未修复时宽高比1.68/2.32/12对应法向误差12.333238/21.359932/56.124828度；修正后都为0。统一缩放宽高比1旧/新均0，解释了为何此前纯文字不容易暴露。

新增TextModelTransformTest回归源码，捕获真实TextModel→DisplayVisual Transformation并按客户端T*L*S*R重建矩阵。验证复合train/car/安装旋转下原版glyph四角、法向和尺寸，另验证uniform scale旧行为等价。生产/测试源码编译通过（12s），JOML诊断执行通过；按用户原约定不执行JUnit统一套件。客户端画面仍需复核。原先只检查未旋转二维墨迹边界的SDL诊断漏过宿主旋转链，是本次漏检原因。

同批保留用户要求的独立9KB显示overlay打包修正，不再把完整列车资源包作为推荐下载。资源包包含必要的屏幕模型覆盖/背板和圈点字体，放在原S100包上方；该资源变更不用于补偿错误宿主矩阵。
