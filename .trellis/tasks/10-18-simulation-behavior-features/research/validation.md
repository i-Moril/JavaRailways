当前最终验收已更新为53源c1d805b9、8模块2507项零失败；见 [父最终记录](parent-acceptance.md) 与 [独立复核](parent-acceptance-check.md)。下文旧候选/实施中列表仅保留历史，不代表当前开放项。

# Current P2 lifecycle fixes (2026-10-20)

Three user findings fixed;52-sourcec781ed4f passed1189 affected-module tests (13 new), API/JAR/package/docs and independent review. Both selected regressions fail against baseline implementations and pass with fixes. See [review-fixes-validation.md](review-fixes-validation.md), [review-final-evidence.json](review-final-evidence.json) and [review-fixes-check.md](review-fixes-check.md). All previous2491/ae862 sections below are historical before-review evidence. No commit/merge/deployment.

# Current upstream-alignment validation (2026-10-20)

Final combined source ae862ac8 on87fe3e45 passed2491 JVM tests plus108 frontend and all API/package/docs gates; see [upstream-alignment.md](upstream-alignment.md), [upstream-final-evidence.json](upstream-final-evidence.json) and independent [upstream-check-result.md](upstream-check-result.md). Previous sections below are historical old-baseline progress, not remaining current failures. No commit/merge/deployment. Fatal host errors retain upstream process-wide halt.

# 第四批验证记录（尚未最终通过）

继承第三批已测a5095090候选；用户要求继续而暂缓合并，实际WT仍为.worktree/simulation-signal-features。

## 首轮A/B定向

- 第一轮SimpleATO lifecycle14真实执行通过；Bukkit编译发现既有forEachExtensionData重载需显式泛型，A/B修正后产品编译通过。
- 第二轮只发现新split fixture的Future<Train>读取实际CoreTrain controller需显式cast，修正未放宽断言。
- 第三轮Bukkit46真实执行通过；SimpleATO14源码未变使用第一轮有效输出。合计60，全部0fail/error，15新增case全部XML实际发现。精确日志/XML与SHA见focused-ab.json（临时原始文件/tmp/simulation-behavior-ab-focused.json及-results）。
- 此后A仅强化2个既有test：真实split后persistent JS计数保持、延迟scene失败PENDING→FAILED，case数仍8。这两项最终字节尚待C及完整门禁重跑，不能把60项提前算作最终候选验证。

## 文档门禁

当前threading-model源 `npm run check` 退出0，typecheck/model validation/schema fixtures/引用复制/Docusaurus/Pagefind27页通过；源/日志SHA见docs-evidence.json。没有安装或升级依赖。后续如文档源再改须重新确认。

## 等待完成

C真实信号/碰撞/时刻表/行为联合fixture、独立全范围最终check、当前源码完整8模块/API边界/jar/Dokka/frontend门禁及最终source/XML SHA均未完成。父任务AC6/AC7未据首轮focused关闭。

## 边界

本地源码及JVM真实领域链不代表Paper/Folia/客户端效果，平台渲染/视距/音频实际播放与玩家会话排除。当前共同旧边角：SpeedSafetyMonitor按trainId读curve offset，LIVE与旧SIM都仅创建/reload/seed写入，真实split新ID没有该store继承，当前两模式行为一致；本批只补动力学TrainProfile/CarProfile和车厢行为结构延续，不扩大为修既有LIVE安全业务，也不声称nonzero curve-offset结构后矩阵已验证。无持久化格式/版本变更，无提交、推送、部署或合并。

环境中断记录：/home/user盘满，workspace仍有65GB。仅本任务Trellis两个channel及两个provider session字节校验后迁到.worktree/simulation-behavior-runtime并保留原路径symlink；满盘导致supervisor退出后，确认证据并结束两个本任务孤儿PID再恢复同一provider session。迁移manifest保存在临时runtime目录。没有清理用户其他数据、改源码或重启服务；原始focused与源码快照在/tmp保留。
