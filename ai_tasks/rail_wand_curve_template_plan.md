# RailWand 曲线模板 / S 弯 / 道岔 / 吸附修复计划

## 背景与问题归纳

当前还有一个暂不处理但必须记录的问题：多线/复线轨道创建仍不满足并行轨道约束，可能出现本应平行的轨道在曲线中途交叉。该问题需要后续在复线约束校验与偏移曲线生成阶段单独处理，不能只依赖起终点横断面间距校验。

当前 `RailWand` 的曲线生成核心在 `Basic/src/main/java/work/art1st/mineRails/basic/curve/RailCurveFactory.kt`：

- 同向端点且 chord 与 tangent 共线时：生成直线模板。
- 同向端点但 chord 与 tangent 不共线时：直接失败，提示“需要 S 弯模板”。
- 端点切线有夹角时：走当前五段式模板 `直线 + 缓和曲线 + 圆曲线 + 缓和曲线 + 直线`。
- `ConstraintSolver.generateCurve` 已把 `StandardRailCurve` 的超高角叠加到最终 `PrimeCurve` normal；自动 Sine/Klein 模式在完整缓和曲线内平滑过渡。

用户反馈的问题：

1. RailWand 无法创建 S 弯。
2. 外轨超高与道岔几何不兼容，道岔需要单独曲线/模板。
3. 有时需要创建斜向直线，但自动吸附/自动模板选择会把它识别成 S 弯或非直线。

结论：需要把“曲线模板选择”从当前的隐式判断，升级为显式、可配置、可预览、可验证的模板系统。

---

## 设计原则

1. **模板显式化**
   - 不再只靠端点 tangent/chord 自动推断。
   - RailWand 提供模板模式：`AUTO`、`STRAIGHT`、`FIVE_SEGMENT`、`S_CURVE`、`TURNOUT`。

2. **吸附拆分**
   - 位置吸附、角度吸附、模板吸附分离。
   - 创建直线时，右键点只定义垂直于起点切线的终点平面，不能直接改写直线方向。

3. **超高作为可选轨道平面参数**
   - 普通曲线可启用超高。
   - 直线默认无超高，除非用户手动设置 roll。
   - 道岔模板默认禁用自动超高，只使用道岔自身几何 normal/roll。

4. **道岔不复用普通圆曲线模板**
   - 道岔是特殊轨道部件，至少需要独立 `TURNOUT` 模板和 metadata。
   - 后续 interlocking/signal/block 可引用道岔 metadata，但第一步只解决几何创建问题。

5. **失败必须可解释**
   - 模板不适配时给出明确提示：例如“当前为 STRAIGHT 模板，但端点过近/高度坡度超限”。
   - 自动模式选错时，玩家可以切换显式模板解决。

---

## Phase 1：引入曲线模板配置与自动分类结果

### 目标

先不实现新几何，只把模板选择机制清晰化，避免继续把逻辑堆在 `RailCurveFactory.build()` 里。

### 新增模型建议

```kotlin
enum class RailCurveTemplateType {
    AUTO,
    STRAIGHT,
    FIVE_SEGMENT,
    S_CURVE,
    TURNOUT
}

data class RailCurveBuildOptions(
    val template: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    val radius: Double = 0.0,
    val superelevation: SuperelevationOptions = SuperelevationOptions.Disabled,
)
```

`RailCurveFactory.build(from, to, radius)` 改为：

```kotlin
RailCurveFactory.build(from, to, options)
```

保留旧签名作为兼容入口。

### 自动分类

新增：

```kotlin
data class CurveTemplateMatch(
    val template: RailCurveTemplateType,
    val reason: String
)
```

自动模式建议规则：

1. chord 近似为 0：失败。
2. 两端 tangent 与 chord 都近似对齐：`STRAIGHT`。
3. 两端 tangent 同向但 chord 不共线：`S_CURVE`。
4. 两端 tangent 有夹角：`FIVE_SEGMENT`。
5. 如果 anchor/metadata 指明 turnout：`TURNOUT`。

### 验收

- `/RailWand` 内部能显示当前模板模式。
- 自动模式失败时，提示“自动选择了 S_CURVE，但该模板尚未实现”而不是模糊失败。
- 不改变现有可成功创建的直线/普通曲线行为。

---

## Phase 2：修复斜向直线创建体验

### 目标

玩家能可靠创建任意水平/斜向直线，不会因为吸附方向而变成 S 弯。

### 行为调整

1. 新增 RailWand 模板设置：`模板 = 自动 / 直线 / 普通曲线 / S弯 / 道岔`。
2. 当模板为 `STRAIGHT`：
   - 创建模式连续建线时，右键点定义一个垂直于起点切线的平面；起点切线与该平面的交点才是终点。
   - 第二组锚点复用第一组锚点/既有轨道端点的 tangent 表示；几何上 `T` 与 `-T` 必须得到同一切线斜率。
   - 直线始终继承起点的三维切线斜率，不提供额外方向策略开关。
   - 生成结果必须是三维直线段，起终点切线相同。
3. 将现有 `snapToAngle` 拆分为位置吸附和 yaw 吸附；直线第二次右键不读取玩家 yaw。
4. 直线模板必须验证终点位于起点切线上，不能为了经过右键点而改变切线。

### 验收

- 创建模式中，第二次点击创建的终点位于起点切线上，并落在经过右键点的切线垂直平面内。
- 终点锚点复用第一锚点的切线表示，不受玩家当前朝向影响。
- 锚点使用 `T`、`-T` 或任意起终点符号组合时，生成的规范方向曲线完全一致。
- 自动模式仍可根据 tangent/chord 判断；同向但横向错位会进入 S 弯模板。

---

## Phase 3：实现 S 弯模板 MVP

状态：已实现首版 Hermite S 弯。`RailCurveFactory` 在 AUTO 模式下对“端点切线同向但 chord 不共线”的情况选择 `S_CURVE`，使用平面三次 Hermite 生成水平线形，并叠加现有纵断面 Hermite 高度函数与 roll 插值；若 `radius > 0`，会把它作为最小半径约束进行曲率检查，不满足则拒绝创建。已添加 `RailCurveFactoryTemplateTest` 覆盖 S 弯生成。

### 目标

支持“端点切线同向，但 chord 与切线不共线”的 S 弯线路。

### 第一版实现建议

先实现稳定 MVP，再追求工程最优线形：

1. 新增 `SCurveTemplate`。
2. 输入：from/to position、from/to tangent、最小半径、采样步长。
3. 生成一条平面 S 曲线，并叠加现有纵断面高度函数。
4. 输出 `PrimeCurve.fromPoints(points)`。
5. 验证：端点位置、端点 tangent、最小曲率半径、采样连续性。

### 可选算法路线

#### 路线 A：平面三次 Hermite / 五次 Hermite MVP

优点：实现快，容易保证端点位置和切线。  
缺点：曲率不一定满足铁路工程线形，需要做最小半径检测。

适合作为第一步：

- 若最小半径满足配置，则接受。
- 若不满足，则提示“半径过小，请拉远锚点或增大偏移距离”。

#### 路线 B：双圆/双缓和曲线 S 弯

优点：更接近铁路工程。  
缺点：求解复杂，需要处理两段反向曲线与中间可能直线段。

建议作为第二步替换/增强。

### 验收

- 平行但横向错开的两组锚点能创建 S 弯。
- S 弯起终点 tangent 与 anchor 一致。
- 路线中途不会出现明显折角。
- 半径不足时拒绝创建，并给出可操作提示。

---

## Phase 4：超高接入与禁用策略

状态：五段式曲线已接入自动超高。自动 `SINE_KLEIN` 模式按 EN 13803 的 Sine/Klein 函数在完整缓和曲线内过渡，超高值、变化率和二阶变化率在直线/圆曲线接缝处连续；`LINEAR` 模式保留显式线性选项。当前仍未按工程规范限制最大超高变化率，过短缓和曲线需要后续增加参数校验。

### 目标

保持超高可配置，并避免自动上限截断造成轨道平面扭转速率突变。

### 新增配置建议

```kotlin
sealed class SuperelevationOptions {
    data object Disabled : SuperelevationOptions()
    data class Manual(val maxMeters: Double) : SuperelevationOptions()
    data class Auto(val designSpeedKmH: Double, val maxMeters: Double) : SuperelevationOptions()
}
```

### 行为

- `STRAIGHT`：默认 `Disabled`。
- `FIVE_SEGMENT`：可 `Auto/Manual/Disabled`。
- `S_CURVE`：第一版默认 `Disabled` 或只按曲率符号平滑 roll，避免反向曲线处超高突变。
- `TURNOUT`：默认强制 `Disabled`，除非未来道岔模板自己定义局部 roll。

### 关键点

- 五段式自动超高必须遵守配置的过渡函数，达到上限时也要使用完整缓和曲线，不能提前硬截断。
- S 弯存在曲率符号反转，超高也必须换向；需要在零曲率附近平滑过渡，不能简单线性插值。否则会出现轨道平面扭转突变。

### 验收

- 普通曲线启用超高后，渲染/列车姿态能体现 roll。
- 道岔模板不会自动套用外轨超高。
- S 弯默认不会出现中间 roll 跳变。

---

## Phase 5：道岔模板独立化

### 目标

道岔不再被普通曲线模板误处理。

### 第一版范围

先设计 geometry/data，不急着接 interlocking。

建议新增：

```kotlin
enum class TurnoutKind {
    LEFT,
    RIGHT,
    SYMMETRIC
}

data class TurnoutOptions(
    val kind: TurnoutKind,
    val straightLength: Double,
    val divergingRadius: Double,
    val frogAngle: Double,
    val gauge: Double = 1.435,
)
```

生成结果可以有两种路线：

1. **MVP：仍创建多条 Core Rail**
   - 一条直股 rail。
   - 一条侧股 rail。
   - 用 Basic metadata 标记它们属于同一个 turnout。
2. **长期：Core 增加 Turnout/SwitchRail 抽象**
   - 更适合物理、寻路和联锁。
   - 但侵入 Core，放后续。

当前建议先走 MVP，以免阻塞 RailWand 可用性。

### 验收

- 创建道岔时使用独立模板和独立参数。
- 不套用普通曲线超高。
- dump/metadata 中能识别同一道岔的直股/侧股。

---

## Phase 6：RailWand UI/预览整合

### 目标

玩家在创建前能知道将生成什么模板，避免“以为直线，实际 S 弯”。

### 行为

1. RailWand 设置页新增：
   - 模板模式
   - S 弯最小半径
   - 超高开关/设计速度/最大超高
   - 道岔参数页
2. 预览显示：
   - 当前模板名称。
   - 如果自动模式，显示自动选择原因。
   - 不可生成时显示错误原因。
3. 创建失败不改变世界状态。

### 验收

- 创建前能通过预览看出直线/普通曲线/S弯/道岔。
- 失败只提示，不留下半创建 rail/render/meta。

---

## Phase 7：复线并行约束与交叉修复

状态：待处理。用户已验证当前多线轨道创建仍存在问题：本应并行的轨道可能变成交叉轨道。短期先记录，不阻塞删除模式与单线/S 弯验证。

### 问题

当前复线创建主要校验：

- 起点组相邻锚点间距。
- 终点组相邻锚点间距。
- 第 `i` 条线使用 `start[i] -> end[i]`。

这不足以保证曲线中途仍保持并行/不交叉，尤其在：

- 五段式曲线不同线各自独立求解半径/缓和曲线长度；
- S 弯不同线各自独立 Hermite 生成；
- 端点 tangent、偏移方向或自动翻转不一致；
- 小半径或大横向间距场景。

### 后续方案

1. 复线不应完全独立求解每条线；应先求解中心线/基准线，再按法向偏移生成并行线，或至少共享模板参数。
2. 增加曲线中途采样校验：
   - 相邻线距离应接近 `parallelSpacing`；
   - 相邻线横向顺序不能反转；
   - 最小距离不能低于安全阈值；
   - 出现交叉时整体失败并回滚。
3. S 弯/五段式都需要复线专用生成入口，而不是简单循环调用单线 `RailCurveFactory.build()`。
4. UI 提示应区分：起终点横断面不合法、曲线中途交叉、半径/间距组合不可行。

### 验收

- `parallelCount=2+` 时，曲线中途不会交叉。
- 如果当前参数无法保持并行，应拒绝创建并回滚。
- 单线逻辑不受影响。

---

## Phase 8：轨道尺子/测距工具

状态：待实现。用户需要一个尺子工具，用于测量轨道上两点之间的间距，辅助验证线路、路径点、停车点、站台长度、闭塞区段长度和复线间距等。

### 目标行为

新增一个轻量测距工具，可作为 RailWand 的新模式或独立 Wand：

- 选择轨道上的第一个点。
- 选择轨道上的第二个点。
- 输出两点间距离。
- 如果两点在同一条连续可达轨道路径上，应优先输出沿轨道弧长距离。
- 同时可输出直线空间距离，方便对比。

### 建议命名

可选：

- `MeasureWand`
- `RulerWand`
- `RailMeasureMode`
- RailWand 新增 `MEASURE` 模式

短期建议先做 RailWand `MEASURE` 模式，复用现有锚点/轨道命中逻辑；如果后续功能变多，再拆成独立 Wand。

### 第一版功能

1. 左键/右键轨道选择测量点。
2. 点位应吸附到最近 `Rail.curve.getNearestPoint(...)`。
3. 保存第一个测量点后，高亮显示。
4. 选择第二点后输出：
   - `railId`；
   - 两点各自 `arcLength`；
   - 同轨距离：`abs(s2 - s1)`；
   - 空间直线距离；
   - 如果跨轨，则尝试用 `RoutePlanner`/RailView 图搜索计算沿轨距离，失败则提示“跨轨路径暂不可达/暂未支持”。
5. Shift+左键或切换模式清空测量状态。

### 数据结构建议

```kotlin
data class RailMeasurePoint(
    val railId: UUID,
    val arcLength: Double,
    val position: Vector3d
)
```

### 后续扩展

- 显示粒子/marker 标出两个测量点。
- 持续显示测量线段/沿轨预览。
- 支持测量路径点之间的轨道距离：`/mr route validate` 已有总长，可做专用 `/mr measure waypoint A B`。
- 支持复线间距采样，辅助修复 Phase 7 的复线交叉问题。
- 支持输出坡度、平均坡度、最大坡度变化率。

### 验收

- 能测量同一条 rail 上任意两点的弧长距离。
- 输出结果足够用于验证站间距/站台长度。
- 测量 marker 只对当前玩家可见，切换工具后消失。

---

## 推荐实施顺序

优先解决“无法创建测试线路”的阻塞：

1. **先做 Phase 1 + Phase 2**：显式模板 + 右键终点平面投影，解决斜向直线被误判的问题。
2. **再做 Phase 3 MVP**：S 弯可创建，使用 Hermite + 曲率检查。
3. **再做 Phase 4**：把超高接入并默认关闭，避免影响道岔/S 弯。
4. **最后做 Phase 5**：道岔模板独立设计。

短期目标不是一次性完成所有铁路工程模板，而是先让 RailWand 有明确、可控、不会误判的建线能力。
