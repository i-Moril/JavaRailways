# 验证结果

## 当前改动
- 前端：Vitest 74 files / 537 tests通过；tsc + Vite build通过。
- `schemas/types/timetable.d.ts` 独立 strict tsc通过。
- `git diff --check`通过；trellis-check全范围审查完成，补齐实际Dispatcher非运营段捕获断言。
- 实际 SimpleDisplays `XmlDisplayService` 编译并绘制三份仓库 XML，使用生产draw预算，未放宽timeout。探针源码在同目录 `DisplayRefreshTemplateProbe.java`，结果在 `template-probe-output.txt`。
- 验证180/120tick语言边界、普通字体、几何字体保留、运营/回送/恢复、内屏EMPTY、外屏隐藏class及blink稳定keys/depth。不能代替真实光影客户端画面验收。

## 后端完整检查

命令：
```
env JAVA_HOME=/home/user/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 \
 GRADLE_USER_HOME=/workspace/minecraft/JavaRailways/.gradle-user-home \
 ./gradlew --offline --console=plain --max-workers=2 --continue \
 -I /tmp/jr-display-refresh-test-heap.init.gradle \
 -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-keyed-maven \
 :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test \
 :BukkitPlugin:compileSignalApiConsumerJava :WebUI:test \
 :Decorations:test :Decorations:verifyPluginJar :BukkitPlugin:shadowJar
```

Core599、Basic494、SimpleATO850、WebUI71、Decorations73项全部通过。BukkitPlugin936项：934通过、1失败、1既有skip。
本次host18、worker25、projection16、浏览器编译fixture1、worker兼容3及实际Dispatcher31项回归均通过。
`compileSignalApiConsumerJava`、Decorations JAR边界检查和Bukkit shadowJar成功。

完整Gradle退出1，不能表述成全绿：唯一失败为 `CarAnimationPlaybackTest.kt:85` 比较样例ZIP中behavior.js与源码。
`baseline-test-failure.json` 直接用 `git show 81c09823` 提取两份原文证明该差异已存在；当前两文件都未改。
不为通过这项既有失败修改超范围行为文件/ZIP。新XML仅更新源文件，未重打包例ZIP。

默认测试堆和共享2GiB JVM先后OOM，无断言失败；最终临时init给Test JVM 2g、SimpleATO每测试类单独进程，通过完整套件。
未改仓库构建配置、排除测试或放宽生产预算。完整控制台日志为 `/tmp/jr-display-refresh-gradle-forked.log`。
结果计数、JAR SHA256和字节数见 `validation-results.json`。

## 服务端边界

三份XML已备份、替换并复核hash，详情在 `server-xml-update.json`。父目录不可写，首次原子替换未改任何原文件；
核对三份原hash及备份完整后改用可写原文件写入+fsync+读回校验。XML在enable/资源重载时捕获，不会在写入期间被draw读取。
本轮没有替换JAR、重启/重载或修改运行存档，现有会话未自动激活新XML。
后续具体文件和授权边界见 `activation-boundary.md`。

## 本轮未触碰
Resources仓库没有XML，保留其未跟踪解压目录。SimpleDisplays及docs-site独立子模块未改；主仓脚本声明已同步。

既有skip：work.art1st.javarailways.bukkit.http.AtoStoppingAccuracyExperiment.measure()
