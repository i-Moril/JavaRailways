# 游戏建造显示修正验证（2026-10-16）

## 范围和实现归属

用户明确要求分别修正UUID干扰、粒子区间/方向标记及Signal Wand缺少区间显示。没有借机重开Core/Basic审查、修改信号授权/JS或存档；未升版本、部署或重启。前一批已部署类加载修复及部署记录先独立提交为`54a10deb`，本轮工作与其区分。

- `40a62c1d`：RailWand普通建造消息、派生端点/分割点及闭塞创建反馈隐藏自动UUID，保留内部身份和真实作者名，中英文参数同步。区间叠加及信号选择文字随后在信号显示批次完整覆盖。
- `44c510bb`：共享EditorTextAppearance/MarkerLabels、host服务外观更新、Java消费者及独立自定义字体资源包；旧五参数标签保持普通文字行为。
- `1e569162`：RailObjectWand运行/到达方向迁移；worker准备、按输入缓存、玩家移动仅筛选，旧粒子动画和相应测试删除。Waypoint原ItemDisplay不变。
- 后续信号显示批次：SectionOverlayDrawing仅保留TextDisplay标签，边界/朝内入口/稀疏覆盖复用共享helper；SectionWandOverlay识别闭塞/保护区域/Signal工具并共用一套handle和缓存，命令发放/设置检查接线；按钮、绑定lore/status和反馈隐藏自动UUID，选择页编号不改变精确绑定。

共享机制和几何原理由[编辑标记契约](../../../spec/bukkit-plugin/backend/editor-markers.md)负责，区间缓存、入口方向和工具生命周期由[信号工具契约](../../../spec/simple-signals/backend/section-authoring-wands.md#持工具的边界与区间编辑叠加current)负责，不在任务中复制接口合同。

## 独立复核与修正

两名独立checker分别复核共享API/资源/RailObject及Signals/RailWand。发现并修正：

- 字形初版中心偏移不能按位图中心猜测。读取Mojang官方26.1.2 client JAR（SHA1 `4e618f09a0c649dde3fdf829df443ce0b8831e65`），用javap核对TextDisplayRenderer、GlyphBitmap、BakedSheetGlyph；修正横向/纵向补偿并新增组合真实客户端矩阵的中心、方向和物理宽长回归。只检查字节码，没有启动客户端，不能等同实际图像验收。
- 补齐保护入口、设备选择页/重绑/新建反馈的生成UUID遗漏，作者名称、管理员诊断、PDC/定义身份不变；同页自动身份使用局部编号区分。
- 原RuntimeSectionOverlayBackendTest要求每个标签都有unavailable文字，不适用于新增图案；改为明确验证可读文字状态及图案RED，同时保留capture失效、曲线调用次数检查。补0.2米短区间正尺寸测试，未增加零长fallback。

官方客户端只作只读布局核验，通过开发服网络直接流到本地/tmp，没有在开发服写文件、改配置或重启。本机直接访问官方域名失败，不将其误报产品问题。PNG的Node生成脚本语法检查通过，重生成SHA256始终`af1c51cab6a8c59a3a3aab8d1427cfc4f0c935d2513b695630fd3127f6e6fa44`。

## 实际门禁与产物

隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`使用/workspace/.gradle缓存和JDK25、offline/no-build-cache、2workers、1536m heap/1024m metaspace。首次55秒：Bukkit409项通过，Signals589项仅上述旧断言1失败；不是忽略红灯。同步checker修正后最终49秒、42tasks（15执行/27up-to-date）：

- Bukkit97套410项、Signals54套589项，共999项，failure/error/skip均0，两个test任务实际执行。
- `:BukkitPlugin:compileSignalApiConsumerJava`、`:BukkitPlugin:shadowJar`、`:SimpleSignals:check`中的实际JAR校验、`:BukkitPlugin:editorMarkersResourcePack`及`verifySignalModuleBoundaries`通过。
- 两模块src、构建文件及编辑资源源树与候选内容一致；初次rsync的chgrp属性错误以--no-group/--no-owner同步后消除，确认内容diff无差异。最终源码未混入无关TODO、物理方案、docs-site或models脏文件。
- 只有frontend安装跳过，未跳过其build，但未改前端输入，buildFrontend/内嵌WebUI任务复用缓存。未重跑Core/Basic/SimpleATO/WebUI测试或npm测试，没有独立lint任务；git diff --check通过，既有JDK/Gradle/Paper deprecated警告未扩修。

完整日志：`/tmp/javarailways-editor-ui-first-check.log`、`/tmp/javarailways-editor-ui-final-check.log`。XML位于候选两个模块的build/test-results/test。

最终SHA256：

- JavaRailways主JAR：`8770d68dc6f5d7d57edcea5f1e944546b0da41923e0217e5c71cc462f37a2f36`。
- SimpleSignals JAR：`4135e84020261180ebb65c85be6da44fbd882fa22ccc78fc6a747f0f4930c723`。
- 26.1.2编辑标记ZIP：`44540a04470c96956f7dff459fd602afee2e5f83919d43e6d813054a91e6de75`；单独强制重建1秒后哈希相同，unzip -t全通过。
- 主JAR内嵌WebUI JAR：`9c43d4bc92d25964867188f94fe7903dc730a546ab765e82d64f3d07da4ca125`，保持此前完整前端产物，不重犯空静态资源打包问题。

## 交付和待验收

资源源树和安装/合并说明为`BukkitPlugin/examples/editor-markers/`，构建输出`BukkitPlugin/build/distributions/JavaRailways-editor-markers-26.1.2.zip`。独立font namespace不覆盖现有列车/轨道/四灯模型资源；需由客户端安装，不新增自动托管/下载配置，也不以缺包粒子fallback冒充成功。

本批未部署。以后部署需同时更新主JAR与SimpleSignals（新公开图案API），客户端启用编辑资源；真实玩家需验证普通建造不再出现UUID、Signal Wand能找到有向入口、不同工具切换、方向/覆盖图案的辨识度、遮挡、超高/坡道、玩家隔离、跨Folia区域及离手清理。999项离线测试不是上述真实游戏验收，Stage 2仍in_progress。

上述是实现/离线验收批次结束时的状态；后续用户单独批准部署，07:46已正常重启，恢复现有定义并通过启动检查，精确备份及运行结果见[部署记录](dev-server-deployment-2026-10-16.md#编辑标记与工具交互修正部署07450747-utc)。真实客户端图像/工具操作仍待验收，未将启动成功当作这些项目通过。

## 区间显示简化：后续截图反馈批次

用户截图显示箭头、双侧虚线及重复文字堆叠，批准改为仅标分界点、连续短横杠描线、不画箭头。已按[区间叠加合同](../../../spec/simple-signals/backend/section-authoring-wands.md#持工具的边界与区间编辑叠加current)修正SectionOverlayDrawing，Backend仅提供captured topology的真实普通连接/LoopAnchor端点身份回调以合并相邻闭塞分界，不改世界图、定义、授权或选轨。所有区间内部箭头/文字删除；字形只将COVERAGE改实心矩形，独立逐像素比对DIRECTION/BOUNDARY与HEAD相同，RailObjectWand方向功能不变。

独立check未发现产品阻塞，修正两处测试：no-arrow改为比较实际glyph字符、不比较带字体/颜色的完整Component；原拓扑测试补LoopAnchor分支断言，无新增测试计数。几何回归覆盖直/弯/坡与0.2米短段、单/双向相同中心线、两端和内部接头、同轨及真实跨轨边界合并、不合并未连轨道、无中段文字，原缓存和生命周期测试保留。node --check、PNG像素验证及git diff --check通过。

沿用前批隔离目录、JDK25与/workspace缓存、offline/no-build-cache、2workers及1536m heap/1024m metaspace。首轮`:BukkitPlugin:test :SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :BukkitPlugin:shadowJar :BukkitPlugin:editorMarkersResourcePack verifySignalModuleBoundaries`42秒通过，Bukkit97套410项和Signals54套592项实际执行且均0failure/error/skip；同步checker的两个测试文件后，最终34秒/42tasks（5执行/37up-to-date），Signals592项实际重跑，Bukkit测试/未变主JAR/Java消费者复用成功缓存，JAR和模块门禁实际执行。未重跑Core/Basic/SimpleATO/WebUI或npm测试，无新lint任务；既有JDK/Gradle警告未扩修。

两模块src、Bukkit编辑资源树与候选diff一致。日志`/tmp/javarailways-section-centerline-check.log`及`/tmp/javarailways-section-centerline-final-check.log`；资源ZIP单独强制重建1秒后哈希相同、unzip -t通过。最终SHA256：

- 主JAR仍为`8770d68dc6f5d7d57edcea5f1e944546b0da41923e0217e5c71cc462f37a2f36`，本批无需重新部署主插件。
- SimpleSignals：`6780bf046b2f8ae09e0a7a3181e94f5684cebd7ab9790b282b77a9e12008d6b6`。
- 编辑标记ZIP：`f3ff10250c1f26a99ed38d86f40b059ea36b068bb6b175281d7da435f9b97dff`。
- 字形PNG：`6c1eb4fca109d363fab9a5c43bdfbb0cc37c923738a0e0bab923e286a4bd56d2`。

本批未部署或重启；需配套更新SimpleSignals与客户端编辑ZIP，不可在旧8米间隔/箭头逻辑下先单换实心字形。交付ZIP仍为BukkitPlugin/build/distributions/JavaRailways-editor-markers-26.1.2.zip。真实客户端连续性、边界效果及可见性仍待验收；没有把离线1002项结果当作游戏画面通过，Stage 2保持in_progress。

用户随后指出测试投入过量：已删除逐段重复渲染数学的几何组合测试，将范围过滤测试从1000米缩至150米并删除固定分段数量断言。保留无箭头/中段文字与边界身份等直接行为检查。上面的1002项是精简前的历史结果；精简后仅静态核对和diff检查，未再次运行测试或构建，产品代码及上述产物hash不变。后续同类显示小改动采用必要的定向检查与客户端验收，不再默认重复跑整模块套件。
