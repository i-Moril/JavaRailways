- [ ] 稳定API契约
- [ ] 错误输出

- [ ] 车辆联结解编
- [ ] 信号系统；出错不应全局停止
  - [ ] 单线多闭塞（暂缓）：讨论跨闭塞的方向协调和同向跟车策略；实现时遵守 [SimpleSignals 单线扩展边界](.trellis/spec/simple-signals/backend/index.md#后续单线支持的边界目标尚未实现)，不预建方向锁或调度框架。
- [x] 限速系数
- [x] 展示实体光照
- [ ] i18n
- [x] 指令提示和补全
- [ ] 列车模型优化(LoD)

- [ ] 声音
- [ ] LCD/LED

- [ ] WebUI重构

- [ ] 列车模型工具

# Known issues
- [x] 删除等操作无法撤销 | [ ] 疑似还是有问题
- [x] Wand的滚轮操作有问题
- TBB Wand浮点数舍入？
- [x] RailObjectWand 右键识别
- [ ] RailWand编辑模式加减号
- [ ] RailWand预览
- [ ] RailObjectWand depot模式滑块改输入数字
- [ ] 道岔后轨道长度限制？

# Improvements
- RailObjectWand 连续设置方向
- 需要一把测量尺
- Waypoint方向显示，在锚点附近不清晰
- 性能优化：统一物理解算->包络计算
- [ ] RailWand 职责拆分（可延期，不阻塞 Stage 2 游戏测试）：优先将显示/预览及其实体生命周期提取为独立内部组件；撤销/恢复另批评估，保持轨道、metadata、运行方向和 Destination 的完整事务，不仅把函数移到模式类再回调主类。
- [ ] TimetableDispatcher 局部整理（可延期，不阻塞 Stage 2 游戏测试）：优先核查 operation ticket 请求、身份检查、轮询/取消的重复机制及 checkpoint 数据映射；保留唯一车次状态机和资源生命周期协调，不将共享 ActiveRun 暴露给多个管理器任意修改，不整体重写。
