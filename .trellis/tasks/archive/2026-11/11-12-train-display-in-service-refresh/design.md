# 设计

## 最小差距与 owner
1. 编译丢弃作者运营开关：在 authoring.ts 生成 segment 时携带 isInService，经两份严格 DTO、领域 segment 及 capture/restore 传递。布尔值必填，没有未知值猜测或旧数据兜底；当前开发格式沿用既有版本。
2. display capture 只读 actual 当前 segment：TimetableRunDisplayContext 与 OperationalPartRenderProjection 增加 isInService。新的 currentRun JSON 自动进入现有 contextKey，不增加另一套缓存或推断。
3. invalidateContext/refreshRendering 提前 retireGenerated 导致新帧完成前黑屏：保留 keyedTexts，复用已有 generation 取消和事务 reconciliation；正常 EMPTY 清理及 lifecycle retirement 保持。
4. XML 决定非运营显示：保留 Decorations 对无 currentRun 的 gate，在模板 render 中处理严格 false。默认字体需同时去掉 Roboto 专用宽度估算，复用原有保守 textUnits helper；保持服务器最新布局。内屏3秒分页不变，语言周期改9秒并同步页窗口计数；外屏6秒。

## 预期文件
- WebUI authoring/types 和已有编译测试：生产与声明新字段。
- BukkitPlugin scriptworker Model/Dsl/相关 fixture，以及公开 script 类型声明：同步严格输入输出。
- SimpleATO TimetableRunMetadata、TimetableSnapshot、TimetableUnitDisplayContext 及相关构造/测试：唯一领域事实和恢复。
- BukkitPlugin OperationalPartRenderProjection 与测试：detached 交付。
- BukkitPlugin ExtensionPartModel 与测试：消除刷新空白窗口。
- 三份 examples XML；相应 CURRENT spec/公开字段说明；任务记录。
- 服务器相同三份 XML，仅备份后原位替换。

没有整体渲染重构、调度器、后端或观众策略变更；不重打包/替换未授权的资源 ZIP，不触碰其他工作树。
