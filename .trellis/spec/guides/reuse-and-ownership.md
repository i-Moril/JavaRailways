# 复用与 ownership

新增代码/API 前快速检查：

- [ ] `rg` 搜索相似类名、函数名、配置 key、JSON 字段和错误消息。
- [ ] 逻辑 owner 是否正确：physics → Core，运营/时刻表 → Basic，Paper/Folia → Bukkit，HTTP → WebUI backend，编辑投影 → frontend。
- [ ] 非 Core consumer 是否可使用 `core.api`/`basic.api`；缺能力时扩展最小 API，而不是导入 internal。
- [ ] add-on 是否可通过 `bukkit.api` service 获取能力，而不是复制主插件实现。
- [ ] 重复的是业务规则还是仅语法；只有共享 owner 清晰、存在多 consumer 或逻辑易错时才抽象。
- [ ] helper 是否会隐藏 thread、unit、direction、revision 或 error contract；若会，保留显式 API。
- [ ] test fixture 是否已有 owner；不要在 Basic/Bukkit 重建 Core 内部 world/train builder。

禁止为“一处调用”创建跨 module 通用层，也禁止复制 serializer/validator 后让两份 contract 漂移。
