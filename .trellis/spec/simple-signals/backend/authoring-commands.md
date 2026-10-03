# 管理命令与定义草稿

## 1. 状态、范围与所有权

CURRENT。SimpleSignals 拥有命令、草稿文件和用户消息；原有 world manager 仍是定义修改的唯一入口。
本合同负责管理员 JSON 编辑工作流，不包含 Wand、脚本加载或世界格式迁移。
模型配置准备与显示生命周期由[模型显示合同](model-presentation.md)负责，命令复用同一个候选事务。

## 2. 模块工作方式

命令解析权限、world 和参数，捕获标识后提交异步任务。
inspect 读取不可变定义摘要；export 用现有 current codec 写出独立草稿；validate 读取草稿并编译候选，但不发布；
apply 把草稿与当前定义按 ID 比较，交给已有 patch 事务取得编辑许可、保存正式 sidecar 和原子替换 source/publication。
异步结果回到玩家 entity scheduler 或控制台 global scheduler 后输出，不等待主线程完成任何编译或 I/O。

## 3. 内部机制与算法

六类定义分别按稳定 ID 建表：region、table、fixed block、fixed-block signal、protection signal、route indicator。
建表前明确拒绝重复 ID，不能让 `associateBy` 隐藏非法定义。
新增和内容不同的对象产生 upsert；只在原定义中存在的对象产生 remove；完全相同的对象不产生操作。
这不是重新安装 world，不绕过原有删除引用、revision 单调、拓扑、依赖和编辑许可检查。
草稿是完整文档，省略既有对象表示删除，不是部分 merge；帮助和 validate 摘要必须说明并显示新增/修改/删除数量。

validate 与 apply 共享候选构造及编译机制；配置模型时还解析已加载的模型、校验所有可能输出的
外观名并计算摆放姿态，但 validate 不创建模型 handle，不取得临时编辑许可，不写正式文件，不调用 source replacement。
它返回候选合法性及受影响轨道，不承诺 apply 时的动态占用、其他编辑或版本仍不变。
查看入口读取已发布 immutable state，不等待 worker 保存时持有的 manager 锁。

## 4. 命令、文件与单位

- `/simplesignals inspect <world>`：定义版本、各类对象及运行状态摘要。
- `/simplesignals export <world> <draft>`：写 `<dataFolder>/drafts/<draft>.json`；目标存在则拒绝，不静默覆盖。
- `/simplesignals validate <world> <draft>`：只验证草稿。
- `/simplesignals apply <world> <draft>`：正式应用。

world 为已加载世界名称或 canonical UUID；玩家和控制台均显式指定。权限 `simplesignals.admin`，default op。
draft stem 匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`，不接受绝对路径、目录、`..` 或符号链接逃逸，不可指向正式 primary/backup。
JSON 使用原有严格 schema 1 codec，不创建第二种 patch 磁盘格式。
草稿 `definitionRevision` 是 export 时的基线，即 expected revision；apply 成功由原事务增加 1。
修改既有对象仍必须提高该对象 revision；字段单位与 current definitions 一致，速度为 m/s、位置为 canonical AB m、提前时间为 s。

内部 `authoringRuntime(worldId)` 从已发布 read view 取得现有 runtime 身份；`authoringPublication()` 一次捕获定义与编译 topology。
`validateDefinitionPatch(worldId, expectedDefinitionRevision, patch, expectedRuntime?)` 与 apply 使用同一个准备函数。
验证成功结果为 `VALIDATED`，携带候选定义和 affected rail IDs；候选的 revision+1 是预览，不表示已提交。
只有 `APPLIED` 表示正式提交。commands 始终把捕获的 runtime 作为 expectedRuntime 传入。

## 5. 不变量、线程与生命周期

文件读取、编码、写入、定义 diff、candidate compile、validation 和 apply 都在 async worker。
玩家消息使用 Adventure Component 与 player scheduler；控制台回复使用 global scheduler。
命令方块或命令矿车等其他 sender 在原命令 owner 上直接拒绝，不把所有非玩家都当作控制台提交 global 回复。
worker 不访问 mutable Bukkit 世界、玩家背包或绘制模型。
已禁用插件不能执行尚未开始的 queued mutation；retired 玩家不接收旧回复。
world unload/reload 不得让捕获的旧工作落到相同 UUID 的新 runtime；复用既有对象身份检查，不新增 lifecycle token。
草稿成功应用后不删除，重复应用因基线过期被拒绝。正式存档的恢复点沿用原有 backup。

## 6. 校验与错误

- 权限、语法、world 或 draft 名称非法：不提交编辑。
- 不支持的 sender：在当前命令线程有限回复并拒绝，不排队 worker/global task。
- 草稿缺失、非普通文件、路径逃逸、错误 world/schema/未知字段：不调用 apply。
- expected revision 过期、无变化或 candidate 非法：原 typed failure，磁盘/source 不变。
- 模型包未准备好、加载失败、模型/外观不存在或定位失败：`VALIDATION_REJECTED`；
  不创建模型，不改变原配置与显示。不带模型的候选可继续使用；移除坏引用无需先修复坏模型。
- 编辑被运营依赖、列车 claim 或其他编辑阻挡：显示原 blocker kind、owner、revision 和 overlap。
- 用户消息每类 identity 至多显示 8 个排序样本；blocker/diagnostic 也至多显示 8 条，标明总数及省略数量。
  每项文本保留最多 256 字符前缀加省略标记，result 诊断正文保留最多 8192 字符前缀加省略标记；增删改摘要在正文之后。
  完整影响和错误仍保留在 typed result；不能为缩短消息删掉安全校验数据。
- 纯 validate 成功：明确提示未预留资源、未检查未来可用性。
- apply 成功：显示新 definition revision；失败继续保留旧 publication/primary/source。

## 7. 性能与退化行为

diff 按定义及比较字段规模线性，不对每类对象重复搜索全表；不新增寻路或逐 tick 任务。
export/read 为 JSON 文档规模线性；validate/apply 的完整编译和影响计算复用 owner 合同。
诊断样本排序为相应 identity 集合规模的 O(N log N)，在 worker 生成；平台回复只接收已截取的文本。
平台线程只做参数与标识捕获、异步调度和有限用户消息，不做世界扫描，不等待 worker future。
运行时列车/信号逻辑不读取 drafts 目录。

## 8. 具体使用情形

正常编辑：export 当前定义，编辑对象并提升其 revision，validate 后 apply 成功，正式文件与 runtime 同步。
线路状态后来改变：validate 时合法，随后运营路线开始使用相关轨道；apply 被拒绝，不能以验证结果强行提交。
非法草稿：基线已过期、删除仍被引用的 block、路径指向正式存档；必须拒绝，不自动合并或覆盖。

## 9. 必需测试

命令注册、权限、玩家/控制台 world 参数；worker 与正确 target 回复；禁用与 world reload 的排队任务。
export current codec、已有草稿不覆盖、非法路径/符号链接、严格 decode/world 检查。
六类 ID diff 的新增/更新/删除及无变化；revision stale；validate 成功/失败均无许可、磁盘和 source mutation。
实际 apply 复用编辑保护及原子提交，并展示 owner/revision diagnostics。

## 10. 迁移、未决事项与 Wrong / Correct

无 schema 升级、旧格式 reader 或兼容 alias。命令只是现有 current model 的作者入口。
Wand、脚本与更友好的表单仍为后续批次；具体模型绑定见其 owner 合同。

Wrong：修改正式 JSON 后调用 install/reload，或 validate 临时占用资源后宣称 apply 已获准。
Correct：编辑独立草稿，以基线版本提交 ID diff，经唯一 patch 事务执行最终检查。
