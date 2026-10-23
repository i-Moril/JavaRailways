# 设计

## 存储与采集
以 PluginErrorLog 和现有文本磁盘日志为基础，保留当前文件格式的可读主体和 errors.log/.1 轮转规则。字段扩展不人为升级 schema。已有文本原样可读，不增加按开发提交分代的兼容 reader。异常应在负责处理结果的边界记录一次并保留 Throwable，避免逐层重复上报。
日志上下文由拥有者传入；采集线程信息必须在发布端获取。磁盘格式化、写入和日志查询在专用 worker/HTTP worker 中执行。保持关闭排空、不递归记录写盘失败的合同。

## 查询链
固定日志来源登记 -> 有界磁盘 reader -> Bukkit HTTP bridge 服务器级只读接口 -> WebUI authenticated proxy -> 独立 LogsPage。主插件和独立插件按各自真实数据目录登记，不依赖所有世界加载成功；需检查来源停用后仍可读取保留文件。WebUI sidecar 自有错误使用其进程本地日志 owner，managed webui.log 作为现有文件来源；external 模式不得假设共享文件系统，验证具体配置边界后保持同等可观测性或明确诊断限制。
查询仅接受来源 ID、受限过滤条件与游标；来源是白名单，不接受文件路径。限制单次扫描/响应字节；提供截断/保留窗口信息，不冒充完整结果。多行堆栈保持事件关联；轮转或文件替换必须使游标明确重置，不静默拼接不同文件。沿用现有会话认证及授权边界。

## 世界问题
不增加历史。逐一审查现有 train configuration/restore/resources/model 与 SimpleSignals installation/topology/preparation/rules/models/revalidation 来源。保留实际对象故障的状态投影；把原始异常及栈放入日志，在世界问题中仅表达当前对象故障和必要定位信息。规则计算失败导致信号当前不可用，允许同时产生一条异常日志和一项对象状态；恢复只清对象状态，不删历史日志。

## 风险与回退
日志读取可能与追加/轮转竞争，按有界快照和游标合同测试。日志内容包含运行细节，只向既有授权会话提供。大量重复异常不允许造成物理线程磁盘等待；不能以静默丢失代替有界处理。保留既有生产故障行为，日志调整不改变安全停机/信号 fail-closed。
回退本分支代码即可，原日志文件继续保留；无世界数据迁移。原工作区 SimpleSignals 未提交修复另行合并，禁止覆盖。

## 实现定案
纯JVM读写复用归属Diagnostics，writer类迁至diagnostics包，现有调用者更新import，不保留旧开发API转发层。磁盘读取合同见.trellis/spec/diagnostics/backend/disk-logs.md，异步写入与1024队列/丢弃计数见.trellis/spec/bukkit-plugin/backend/error-logging.md。WebUI自身日志使用draft目录同级logs/errors.log；managed与external均本地读本进程日志，宿主来源通过bridge读取，宿主失败显式warning保留本地来源。错误输入JSON不记录原始parser exception，避免输入内容进入日志。
