# 轨道曲线编辑器设计讨论

## 现状

当前 `RailCommand` 只支持直线轨道——从玩家位置+朝向+长度生成 `Curve.RawPoint` 列表。

```kotlin
// RailCommand.createStraightCurve — 写死的直线生成
val points = (0..steps).map { i ->
    Curve.RawPoint(
        position = startPos + direction * length * (i / steps),
        tangent = direction,
        normal = (0, 1, 0),
        arcLength = i * step
    )
}
```

## 真实轨道几何

铁路由三种基本元素组成，**不是样条曲线**：

| 元素 | 曲率 κ | 参数 |
|------|--------|------|
| 直线 (line) | κ = 0 | 长度 L |
| 圆曲线 (arc) | κ = 1/R 常数 | 半径 R、转角 θ |
| 缓和曲线 (clothoid) | κ: 0→1/R 线性变化 | 长度 L、终半径 R |

### 为什么必须用缓和曲线

直线直接接圆弧 → 曲率从 0 突变到 1/R → **曲率不连续 (C0)** → 列车经历横向冲击（无穷大加加速度 jerk）。

缓和曲线让曲率线性过渡，实现 **C2 连续**（曲率本身连续，曲率变化率也连续）：

```
直线(κ=0) → 缓和曲线(L₁, κ:0→1/R) → 圆弧(R, κ=1/R 常数) → 缓和曲线(L₂, κ:1/R→0) → 直线(κ=0)
```

### 曲率连续 vs 几何连续

- **C0**: 位置连续 — 曲线不断开
- **C1**: 切线连续 — 无尖角
- **C2**: 曲率连续 — 无横向冲击（铁路最低要求）

样条曲线（Catmull-Rom、Bézier 等）最多保证 C1 连续，不满足铁路 C2 要求。

## 锚点模型

### 概念

玩家创建**锚点**（包含位置 + 切线方向），曲线连接两个锚点。

### 约束分析

给定锚点 A(P_A, T_A) 和 B(P_B, T_B)，用 `clothoid → arc → clothoid` 连接：

```
参数: L₁(缓和曲线长), R(圆弧半径), θ(圆弧转角), L₂(缓和曲线长)
约束:
  1. 总转角 = ∠(T_A, T_B) → L₁/(2R) + θ + L₂/(2R) = Δψ
  2. 位移 = P_B - P_A        → Frenet 标架沿曲线积分 = 位移向量
自由度: 4 参数 - 3 约束 = 1 → R（圆弧半径）
```

### 可调整参数

| 参数 | 含义 | 如何确定 |
|------|------|---------|
| **R** | 圆弧半径 | 用户选择的自由参数 |
| L₁, L₂ | 缓和曲线长 | 默认 L=R/9，可手动覆盖 |
| θ | 圆弧转角 | 由约束方程解出 |

### 解的存在性

给定 R 的可能结果：
- **θ > 0**: 正常解，曲线包含完整的三段 (clothoid → arc → clothoid)
- **θ = 0**: 刚好两个缓和曲线对接（无圆弧段），最小转弯情况
- **θ < 0**: R 太大，给定的切线夹角无法用这个半径弯曲实现，需减小 R
- **无解**: 两点间距太短、切线夹角太大，任何 R 都无法满足

## 并行线路

复线不能简单平移——内外轨半径不同：

```
R_outer = R + d/2
R_inner = R - d/2
```

其中 d 是线间距（需考虑曲线加宽）。

缓和曲线同理——内外线长度不同但须保证**同转角**。

### 配置表示（建议）

```yaml
# rails/routes/route.yml
config-version: 1
name: main_route
track_spacing: 4.5      # 线间距 (m)
rails:
  - curve: main_line
    offset: 0            # 中心线
  - curve: main_line
    offset: -4.5         # 左侧复线
  - curve: main_line
    offset: 4.5          # 右侧复线
```

系统自动计算内外轨的不同半径和缓和曲线长度。

## 锚点与曲线的 YAML 表示（建议）

### 锚点

```yaml
# rails/anchors.yml
config-version: 1
anchors:
  - id: A1
    position: {x: 100, y: 64, z: 200}
    direction: {x: 1, y: 0, z: 0}    # 切线方向
  - id: A2
    position: {x: 200, y: 64, z: 250}
    direction: {x: 0.707, y: 0, z: 0.707}
```

### 曲线（引用锚点 + 选择半径）

```yaml
# rails/curves/main_curve.yml
config-version: 1
name: main_curve
from: A1
to: A2
radius: 300.0
clothoid_length: 33.3    # 可选，默认 L=R/9
```

### 轨道（曲线的逻辑分组）

```yaml
# rails/tracks/main_track.yml
config-version: 1
name: main_track
curves:
  - main_curve
  - curve_2
  - curve_3
```

## 锚点方向来源

混合方案：
1. **游戏内放置**: 默认取玩家 yaw 方向作为切线方向
2. **YAML 定义**: 如果未指定 direction，自动用连线方向（起点→终点的投影方向）
3. **手动输入**: `/mr rail anchor set_dir <id> <yaw>`

## 命令设计

保留 `/mr rail create` 的简单直线创建。

新增子命令：

```
/mr rail anchor add [id]     — 在玩家位置创建锚点，切线方向取玩家 yaw
/mr rail anchor list         — 列出所有锚点
/mr rail anchor remove <id>  — 删除锚点
/mr rail anchor info <id>    — 查看锚点详情

/mr rail curve create <name> <from> <to> <radius> [clothoid_len]
                              — 在两点间创建 clothoid→arc→clothoid 曲线
/mr rail curve list          — 列出所有曲线
/mr rail curve info <name>   — 查看曲线详情（半径、转角、长度等）

/mr rail track build <name>  — 从曲线定义生成实际 Rail + 渲染
```

## 不同高度（纵断面 Vertical Alignment）

铁路分别处理**平面线形**和**纵断面线形**：

| | 平面 (horizontal) | 纵断面 (vertical) |
|---|---|---|
| 元素 | 直线→clothoid→圆弧→clothoid→直线 | 直坡段→抛物线竖曲线→直坡段 |
| 控制点 | 曲率连续 (C2) | 坡度变化率恒定 |
| 影响 | 横向舒适度 | 竖向加速度 |

竖曲线**不需要 clothoid**。两段坡度 g₁ 和 g₂ 之间用抛物线连接：

```
y(x) = g₁·x + (g₂ - g₁)/(2·L_v) · x²

L_v = R_v · |g₂ - g₁|
```

其中 R_v 是竖曲线半径（远比水平曲线大——高速铁路最小约 5000m，普通线路约 2000m）。

### 数据模型支持

`Curve.RawPoint` 的 `position.y` 和 `tangent`（含 Y 分量）已经可以表达三维曲线：

- 平面线形计算 X, Z 坐标
- 纵断面线形叠加 Y 坐标
- 最终合成为完整的 3D 点列

```kotlin
// 平面曲线 → x, z
// 竖曲线 → y(x) = g₁·x + (g₂-g₁)/(2·L_v)·x²
// 合成
RawPoint(Vector3d(x, y, z), tangent_3d, normal, arcLength)
```

## 外轨超高 (Superelevation / Cant)

### 原理

圆曲线上外轨比内轨高，抵消离心力。等超高公式（标准轨铁路）：

```
h_eq = 11.8 × V² / R    [mm]

V: 设计速度 (km/h)
R: 曲线半径 (m)
h_eq: 平衡超高 (mm)
```

实际中：
- 最大超高 ~150-180mm（标准轨，对应 ~0.15-0.18 方块）
- 缓和曲线上**线性过渡**：0 → h_max
- 圆曲线上保持常数 h_max
- 超高变化率（twist）有限制：通常 ≤ 1:300（每 300mm 水平距离超高变化 ≤ 1mm）

### 数据模型支持

`Curve.RawPoint.normal` 已逐点独立，天然支持超高：

```kotlin
// 直线上：normal 垂直向上
normal = (0, 1, 0)

// 圆曲线上：normal 绕 tangent 旋转 bank_angle
bank_angle = atan2(h_max, gauge)
normal = tangent.rotate(bank_angle, tangent) * (0, 1, 0)

// 缓和曲线上：线性插值 bank_angle
h = (arcLength / clothoid_length) * h_max
bank_angle = atan2(h, gauge)
```

**不需要修改任何数据结构**。只需在生成 RawPoint 时按曲率和设计速度正确计算 normal。

### 锚点中的表示

```yaml
# rails/curves/main_curve.yml
config-version: 1
name: main_curve
from: A1
to: A2
radius: 300.0
design_speed: 80          # km/h, 用于计算超高
cant: 0.15                # 最大超高 (m), 可选, 自动计算时用 design_speed
clothoid_length: 33.3
vertical_radius: 3000     # 可选, 竖曲线半径 (m)
```

## 实现优先级

| 优先级 | 内容 |
|--------|------|
| P1 | 曲线数学库：clothoid 积分计算（Fresnel 积分），给定 R 求解 arc 转角 |
| P2 | 锚点系统：数据模型 + YAML 加载 + 命令 |
| P3 | 曲线定义：数据模型 + YAML 加载 + 命令 + 约束求解 |
| P4 | RawPoint 生成：line/clothoid/arc/clothoid/line → PrimeCurve |
| P5 | 纵断面叠加：坡度 + 抛物线竖曲线 |
| P6 | 超高计算：按 R + V 计算 cant，normal 线性插值 |
| P7 | 游戏内锚点放置（棍子工具） |
| P8 | 并行线路支持 |
