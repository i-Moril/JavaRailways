# Bug Analysis: 路线坐标与停车容差混用

## 1. Root Cause Category

- B / Cross-Layer Contract：Basic 用图边距离搜索、SimpleATO 用轨道段和累计进度执行；原始端点没有在往返换算后保真。
- E / Implicit Assumption：认为停车允许偏差也可用于任意路线段归属，且截回端点不会改变信号前后语义。
- D / Test Coverage Gap：此前没有同时验证极小端点差异、实际越过信号和停车偏差后的下一 Mission。

## 2. Why Fixes Failed

1. 早期只修改资源申请，没有处理实际车头被投影回信号处的问题。
2. 前轮直接将投影改为严格区间，虽消除了截断，却暴露源端点精度丢失及合法停车后起点衔接的依赖；767 项测试有15项失败，不能据9项局部测试交付。
3. 本轮首次停车起点修复先搜索整条路线，再处理首段欠停，审查指出重复轨道时会跳圈；新真实环线回归在修复前得到1段而非3段，随后调整分支顺序。

## 3. Prevention Mechanisms

- P0 / producer：Basic 普通图弧展开保留已知查询端点，精确断言覆盖适配前后；没有用放大容差替代。
- P0 / geometry：真实位置不截断；下游范围校验继续拒绝越界，不放宽 ATP 或 inspection API。
- P0 / operations：已有停车容差仅用于停车语义，复用实际物理起点和原冻结路线衔接机制。
- P1 / tests：相邻段与重复轨道、AB/BA、停车前后偏差、车体范围、几何接缝和原全局场景分别验证；最终执行记录见 verification.md。

## 4. Systematic Expansion

只检查本任务直接坐标链路，不全库删除 epsilon/coerceIn。已保留控制目标区间裁剪和实际停车容差；数值接缝使用既有精度但不改变实际车身位置。后续全局延误并未定位，不能假定与改动无关，也不能把它与原信号错误混为一谈。

## 5. Knowledge Capture

- 已更新 Basic route-planning 的原始端点机制。
- 已更新 SimpleATO ato-policy-runtime 的真实投影、停车衔接、数值边界和 fail-closed 合同。
- 已更新 Dispatcher 停站前缀复用与重复轨道首段优先级。
- 本仓库不是 Trellis 模板工程，无需同步不存在的模板目录；本任务不自动提交、归档或部署。
