# 实施计划

状态：实现、独立审查、自动测试和本机浏览器验收完成；用户已确认本地提交。

1. 核验基线、现有 dirty 边界和各模块异常入口，保存覆盖清单；记录独立插件与 managed/external WebUI 的日志目录、logger 和认证合同。
2. 由 Trellis implement agent 完成磁盘日志增强及异常出口修正、固定来源查询与 bridge/proxy；主会话审阅存储和线程边界。
3. 完成独立日志页、正式导航、API/types、中文英文文案；补齐分页/筛选/堆栈、空状态与读取失败行为。
4. 审查世界问题生产者与恢复链，修正职责混用，更新 owner 规范与必要回归；不改现有操作提示文案。
5. 由 Trellis check agent 独立验证范围、线程/认证/轮转语义和测试；修复发现后复核。
6. 运行 :BukkitPlugin:test :WebUI:test 及实际修改的 Core/Basic/SimpleATO/SimpleSignals/附属模块测试，前端 npm test 与 npm run build，git diff --check。按仓库现有工具链运行，报告真实执行结果。
7. 汇总改动、覆盖/未覆盖入口、验证和上线验收限制。用户未要求部署，不操作服务器。

## 必测场景
原 Throwable/cause/suppressed、模块归属、后台错误、无 Throwable warning；异步写入/关闭/写盘失败；来源白名单与未授权；重启/轮转/并发追加/超长记录/游标失效；前端纯文本安全展示和迟到响应；内容故障恢复清理与纯异常不入世界列表。

## 阶段门禁
依 trellis-brainstorm，向用户呈现本方案后取得一次明确确认，再 task.py start 并派发实现。实现前细读各 touched layer specs；前端浏览器验收需加载 agent-browser skill。不预先声称测试通过。

## 完成记录
步骤1–6已完成，具体测试/打包/浏览器证据及限制见research/validation.md与research/runtime-coverage.md。步骤7已整理。项目Phase3.4要求提交计划确认，当前未提交或归档，工作区可直接审阅。
