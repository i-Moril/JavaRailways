# 本机浏览器验收

2026-10-23，真实构建的 WebUI shadow JAR + 生产 React bundle；agent-browser 独立会话 jr-error-log-check、系统 Chromium。测试服务仅监听127.0.0.1:18880，隔离目录 /tmp/jr-log-browser。上游是仅用于本机测试的桥接 fixture，认证返回测试管理员，worlds与host logs均返回503；不是在线服务器或Paper/Folia验证。

- fragment交换得到真实WebUI HttpOnly session后，worlds503没有撤销认证，服务器日志导航存在，世界选择禁用。
- 来源清单保留webui-errors并明确显示host sources unavailable警告。
- 初轮探索向本机草稿接口发送故意无效JSON，实际Javalin/Jackson产生JsonEOFException，经PluginErrorLog异步落盘，再经真实/api/v1/logs读取并展开显示模块、请求路径和异常栈。
- module=WebUI关键词能找到记录；日志文本通过React文本节点展示。
- 共111条真实解析异常形成579975字节，首批扫描524288字节/100条，第二批57475字节/11条，普通记录无partial标记，最后一页禁用继续；没有跨页丢失这111条完整记录。
- 对该隔离测试日志执行受控rename至.1并创建新当前文件后，旧游标明确返回reset，页面显示轮转/替换需刷新；新查询能找到新文件记录。
- 截图/tmp/jr-log-browser/logs-page.png经目视检查，来源警告、筛选、记录和分页布局正常。顶部既有操作错误提示原样保留，属于用户明确推迟的第3项。

后端最终重打包后的轻量复验及测试计数见validation.md。本机fixture不会证明Bukkit实时日志源、Folia线程与真实玩家客户端已验收。

最终实现随后将无效请求JSON归类为400并不记运行错误；最终产物改用损坏的隔离磁盘测试文件GET复验真实IO异常，结果见validation.md。首轮分页与轮转数据仅为本机测试记录，不是生产数据。
