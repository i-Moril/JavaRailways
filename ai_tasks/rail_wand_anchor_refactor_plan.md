# RailWand 锚点/复线/预览修复计划

## 背景

当前 RailWand 的锚点实现更像“持久化草稿点”：锚点独立保存到磁盘，轨道创建后与锚点脱钩；创建锚点使用玩家当前位置；多锚点连接按 `[A,B] [C,D]` 两两配对；没有创建过程中的实时曲线预览。

目标是把锚点改成轨道编辑模型的一部分：轨道端点一定是锚点，编辑锚点等价于编辑轨道端点/轨道几何；未生成轨道前的锚点只是当前玩家会话里的临时控制点。

## 目标行为

新增 UI/可视化要求：

- 编辑锚点页面需要支持完整欧拉角编辑：yaw、pitch、roll，不能只有 yaw。
- 位置编辑中，X/Z 不适合大范围滑动条，应改为文本框，并提供并排快捷按钮：`+1` / `-1`。
- 欧拉角编辑也应改为文本框，并提供并排快捷按钮：`+15°` / `-15°`。
- 锚点可视化需要更大，并明确显示 tangent/方向。

1. **锚点与轨道绑定**
   - 轨道端点一定对应锚点。
   - 已创建轨道的锚点不再作为独立对象持久化，而是从轨道/轨道编辑元数据推导或绑定保存。
   - 创建轨道前，玩家选择/放置的锚点只存在于内存中的会话状态，不写入 `plugins/JavaRailways/anchors/*.json`。
   - 锚点不需要全局编号；UI 可用临时序号/颜色/位置提示，但不作为持久 ID。

2. **锚点创建来自右键目标方块**
   - 右键方块时，锚点位置应来自点击方块/点击面，而不是玩家脚下坐标。
   - 右键空气可作为可选快捷行为，例如使用视线 ray trace 到方块或提示“请右键方块”。
   - 保留吸附设置，但吸附基准应是点击点/点击面。

3. **复线连接语义修正**
   - 选中 `A,B,C,D` 时，应连接：
     - `A -> C`
     - `B -> D`
   - 即前一组点是起点横断面，后一组点是终点横断面，而不是旧逻辑的 `A -> B`, `C -> D`。
   - 当选择数量为 `2 * parallelCount` 时：
     - 起点组：前 `parallelCount` 个点
     - 终点组：后 `parallelCount` 个点
     - 第 `i` 条线：`start[i] -> end[i]`
   - 对于复线，`A,B` 与 `C,D` 两个横断面，以及 `A-C`、`B-D` 两条曲线都必须满足 RailWand 复线约束。

4. **实时粒子预览**
   - 创建模式下，选择起点后移动视线/右键目标方块时，实时显示预览曲线。
   - 预览应使用当前 RailWand 设置：曲线半径、复线数量、复线间距、吸附规则等。
   - 预览只显示给当前玩家，不持久化。

5. **多曲线模板**
   - 当前只有五段式 `line + clothoid + arc + clothoid + line`，无法表达所有合法编辑。
   - 需要新增模板选择/自动匹配机制，至少包括：
     - 直线模板：端点切线与 chord 共线时使用。
     - 五段式单向弯模板：当前已有模板。
     - S 弯模板：两端切线同向但 chord 与切线不共线时使用；如果没有该模板，应拒绝编辑，而不是把直线错误拉成斜线。
     - 反向/掉头模板：两端切线接近反向时使用。
     - 仅纵断面模板：水平线形不变、只调整高度/坡度时使用。
   - 模板选择失败时必须事务回滚，不能保留中间 rail/render/meta 状态。

6. **纵断面模板**
   - 当前坡度使用全段三次 Hermite：坡度在整条轨道上持续变化。
   - 需要新增铁路工程更常见的纵断面模板：
     - `竖曲线过渡 -> 固定坡度 -> 竖曲线过渡`。
   - 该模板应可配置：
     - 入口竖曲线长度或竖曲线半径；
     - 出口竖曲线长度或竖曲线半径；
     - 是否自动求解中间固定坡段；
     - 最大坡度；
     - 最大坡度变化率；
     - 无法满足约束时是否回退 Hermite 或直接失败。
   - 直线模板和五段式平面模板都应能选择纵断面模板。

7. **RailWand 设置多页面**
   - 当前设置都放在单个 `wand.settings` 页面，模板参数继续增加后会过于混乱。
   - 需要将配置拆成多页面/多 group，例如：
     - 基础设置：吸附、自动装饰、预览开关等。
     - 复线设置：并行数量、间距、复线约束。
     - 平面模板：直线、五段式、S 弯、掉头等参数。
     - 纵断面模板：Hermite、竖曲线-固定坡-竖曲线等参数。
     - 显示/调试：锚点显示、粒子预览、debug 日志。
   - 每种模板的配置应在自己的页面里，避免不相关参数同时出现。

8. **每玩家 RailWand 设置持久化**
   - 当前 `WandPlayerState` 是内存状态，重启/重进后丢失。
   - 需要为每个玩家保存 RailWand 设置，例如：
     - `plugins/JavaRailways/wand-settings/<player-uuid>.json`
   - 至少保存：
     - 当前模式/常用模式设置；
     - `parallelCount`、`parallelSpacing`；
     - `snapToAngle`、`snapToBlock`；
     - `curveRadius`；
     - 当前平面模板类型及参数；
     - 当前纵断面模板类型及参数；
     - 预览/显示设置。
   - 运行时修改设置后应及时保存，玩家加入时加载。

9. **轨道创建/编辑改为异步任务**
   - 当前曲线求解、采样、render meta 构建部分仍可能在 Bukkit 主线程执行，复杂曲线/长轨道/多复线时可能卡顿。
   - 需要将耗时部分拆成异步任务：
     - 曲线约束求解；
     - 曲线采样；
     - 复线约束检查；
     - 预生成 render meta 数据。
   - Bukkit 主线程只执行必须主线程的部分：
     - 创建/替换 `CoreRail`；
     - 注册/注销展示实体；
     - 写入世界状态；
     - 玩家消息和 UI 更新。
   - 异步任务必须支持事务语义：
     - 求解失败不改变当前世界；
     - 主线程应用阶段失败时回滚；
     - 玩家切换世界/退出/取消任务时不应用结果。
   - UI 上应提示任务状态：
     - `正在计算轨道...`
     - `创建成功`
     - `创建失败，已回滚`

## 建议分阶段实施

### Phase 1：拆分临时锚点与轨道端点锚点

状态：已开始实现。RailWand 已停止使用 `PersistentAnchorStore` 读写锚点，改为插件运行期内存 `transientAnchors`；创建、选择、编辑、删除、undo/redo 都只操作临时锚点，不再写入 `plugins/JavaRailways/anchors/*.json`。轨道端点绑定式锚点仍待实现。

#### 数据模型

新增会话级锚点类型，例如：

```kotlin
data class WandAnchor(
    val position: Vector3d,
    val tangent: Vector3d,
    val source: AnchorSource,
    val boundRailId: UUID? = null,
    val boundEnd: RailEnd? = null
)
```

其中：

```kotlin
enum class AnchorSource { TEMP, RAIL_ENDPOINT }
enum class RailEnd { A, B }
```

处理原则：

- 临时锚点只放在 `WandPlayerState` 中。
- 删除/禁用 `PersistentAnchorStore` 在创建流程中的使用。
- 已有轨道端点锚点由轨道数据生成，不单独保存为 anchor JSON。
- 可以先保留旧 `PersistentAnchorStore` 文件读取但不再写入，后续再迁移/删除。

#### 改动范围

- `RailWand.kt`
  - `selectedAnchors: MutableList<String>` 改为保存 `WandAnchor` 或会话内 anchor token。
  - `createAnchorAtPlayer()` 改为 `createTempAnchorFromClick()`。
  - `findNearestAnchor()` 改为从：
    1. 当前会话临时锚点；
    2. 当前世界已有轨道端点；
    中查找。
- `PersistentAnchorStore.kt`
  - 暂时不删，但 RailWand 创建模式不再依赖它。

#### 验收

- 创建锚点不再生成/修改 `plugins/JavaRailways/anchors/<world>.json`。
- 重启后，未创建轨道的临时锚点消失。
- 已创建轨道的端点仍可被 RailWand 识别为可选锚点。

---

### Phase 2：点击方块创建锚点

状态：已开始实现。当前 RailWand 创建模式右键会优先使用右键目标方块/面中心；右键空气会 ray trace 到玩家视线目标方块。`snapToBlock=false` 时会尝试使用 ray trace 精确 hit position。

#### 行为

- `RIGHT_CLICK_BLOCK`：使用 `event.clickedBlock` 与 `event.blockFace` 计算锚点位置。
- 推荐默认位置：点击方块上表面中心或点击面的中心，例如：
  - 点上表面：`blockX + 0.5, blockY + 1.0, blockZ + 0.5`
  - 点侧面：对应面的中心。
- 如果 Paper/Bukkit event 可取得精确 hit position，则优先使用精确 hit；否则用 clicked block + face。
- `RIGHT_CLICK_AIR`：可 ray trace 玩家视线到方块，找不到则提示。

#### 验收

- 玩家站在哪里不影响锚点位置。
- 右键同一个方块/面时锚点位置稳定。
- `snapToBlock` 开启时仍对齐方块中心/面中心；关闭时可用精确 hit point。

---

### Phase 3：重写根据锚点创建轨道的配对逻辑

状态：已开始实现。当前连接逻辑已改为按 `parallelCount` 分组：`A,B,C,D` 在 `parallelCount=2` 时创建 `A->C` 与 `B->D`；创建模式每次点击会以点击/吸附坐标为中点，沿锚点 tangent 的垂直方向创建 `parallelCount` 个锚点，并在复线连续创建时保留终点组作为下一段起点组。已加入起点组/终点组相邻锚点间距校验，曲线中途复线间距采样校验仍待补充。

#### 连接语义

替换当前：

```text
[A, B, C, D] -> A-B, C-D
```

为：

```text
[A, B, C, D] -> A-C, B-D
```

通用规则：

```text
selected.size == 2 * parallelCount
starts = selected[0 until parallelCount]
ends   = selected[parallelCount until 2 * parallelCount]
rail[i] = starts[i] -> ends[i]
```

对于单线：

```text
[A, B] -> A-B
```

#### 复线约束

新增校验函数，例如：

```kotlin
validateParallelAnchors(starts, ends, state): ParallelValidationResult
```

应至少检查：

- 起点横断面内相邻锚点间距接近 `parallelSpacing`。
- 终点横断面内相邻锚点间距接近 `parallelSpacing`。
- 起点组/终点组的横向排列方向一致，避免交叉连接。
- 每条线的曲线长度/曲率/半径满足当前设置。
- 多条线之间采样点距离大致保持复线间距，避免曲线中途交叉或大幅偏离。

实施上可以先做基础校验：横断面间距 + 连接不交叉；再补曲线采样约束。

#### 验收

- `parallelCount = 2`，选择 `A,B,C,D` 创建 `A-C` 和 `B-D`。
- 旧错误行为 `A-B`、`C-D` 不再出现。
- 如果 `A,B` 或 `C,D` 间距不符合设置，创建失败并提示原因。

---

### Phase 4：轨道端点锚点绑定与编辑

#### 行为

- 选中已有轨道端点锚点后，编辑该锚点应更新关联轨道的端点。
- 如果一个端点连接多条轨道，编辑该锚点应更新所有绑定到该端点的轨道，以保持拓扑连接。
- 更新轨道几何后：
  - 重新生成 `PrimeCurve` / `CoreRail` 必要数据；
  - 刷新 render meta；
  - 保持或重建 rail connection；
  - 更新列车安全策略需要另行考虑，初期可禁止编辑有列车占用的轨道。

#### 数据持久化建议

在 rail render/edit meta 中保存创建参数，而不是独立 anchor 文件：

```kotlin
data class RailEditMeta(
    val railId: UUID,
    val start: EndpointSpec,
    val end: EndpointSpec,
    val curveRadius: Double,
    val parallelGroupId: UUID?,
    val laneIndex: Int?
)
```

如果现有 `PersistedRailRenderMeta` 不适合承载编辑信息，可以新增独立：

```text
plugins/JavaRailways/rail_edit_meta/<world-uuid>.json
```

#### 验收

- 编辑轨道端点后，轨道实际物理路径和显示路径同步变化。
- 与该端点连接的相邻轨道仍连接。
- 保存重启后，端点锚点由轨道 meta 恢复。

---

### Phase 5：锚点编辑 UI 改造

状态：已开始实现。单锚点编辑页的 X/Y/Z/Yaw 已从滑动条改为文本输入，并新增 Pitch 文本输入；确认时会解析文本并校验数字。`+1/-1`、`+15°/-15°` 快捷按钮以及 Roll 仍待实现（当前 Anchor 数据模型只有 tangent，没有 roll 存储）。

#### 行为

- 编辑页面字段：
  - position: X, Y, Z。
  - rotation/tangent: yaw, pitch, roll。
- X/Z/Y 坐标使用文本输入而不是 `numberRange` 滑动条。
- 每个坐标旁提供快捷调整按钮：`-1`、`+1`。
- 欧拉角使用文本输入而不是滑动条。
- 每个角度旁提供快捷调整按钮：`-15°`、`+15°`。

#### 技术备注

当前 `DialogRenderer` 对 `ConfigValueType.NUMBER` 固定渲染为 `DialogInput.numberRange(...)`，所以需要扩展 Config UI 表达能力，例如：

```kotlin
ConfigConstraints.NumberText(...)
ConfigConstraints.NumberStepper(step = 1.0)
ConfigConstraints.AngleStepper(stepDegrees = 15.0)
```

或者为 RailWand 锚点编辑单独实现一个专用 Dialog，不走通用 `ConfigKey -> DialogInput` 映射。

#### 验收

- 锚点编辑不再用大范围滑动条修改 X/Z。
- yaw/pitch/roll 都可编辑。
- `+1/-1` 和 `+15°/-15°` 快捷操作可用。

---

### Phase 6：锚点显示增强

状态：待实现。建议将锚点展示从 0.5 缩放放大到 1.1，并额外在 tangent 方向显示方向 marker。

#### 后续可选增强

- 使用粒子或多段 marker 显示更连续的方向箭头。
- 根据选中状态切换颜色/材质，而不只使用 glowing。
- 将 direction marker 旋转到 tangent 方向，而不是只用“前方偏移点”表达方向。

---

### Phase 7：实时粒子预览

#### 触发

- 创建模式：
  - 已有起点组时，玩家看向目标方块，预览起点组到目标组的曲线。
  - 连接模式：选点数量不足完整组时也可以预览下一步。

#### 实现建议

- 每玩家维护一个预览 task，周期 2~5 tick。
- 使用 `player.spawnParticle(...)` 只发给当前玩家。
- 预览曲线通过当前 `createCurveBetweenAnchors()` 生成，但不要注册 `CoreRail`。
- 粒子颜色/类型：
  - 合法曲线：绿色/黄色。
  - 不满足约束：红色，并 actionbar 显示失败原因。
- 采样间距建议 1~2m，避免粒子过多。

#### 验收

- 选中起点后，看向不同方块时预览实时变化。
- 修改 RailWand 设置后，预览立即反映半径/复线设置。
- 预览不会生成轨道、锚点或磁盘文件。
- 玩家切换模式、取消选择、退出服务器时预览清理。

---

## 推荐提交拆分

1. `Refactor wand anchors to session state`
   - 临时锚点内存化，停止写 anchor JSON。
2. `Create wand anchors from clicked blocks`
   - 使用右键目标方块/面创建锚点。
3. `Fix parallel anchor pairing semantics`
   - 修正 `A,B,C,D -> A-C,B-D`，加入基础复线约束校验。
4. `Bind rail endpoints as editable anchors`
   - 从已有轨道端点生成可选锚点，编辑端点更新轨道。
5. `Add rail wand particle curve preview`
   - 创建模式实时预览。

## 风险点

- 编辑已存在轨道会影响列车状态，初期建议禁止编辑被 train 占用/附近有 train 的轨道。
- 重建 `CoreRail` 时必须维护连接关系和 render meta，否则可能再次出现视觉连接但 Core 不连接的问题。
- 多玩家 RailWand 预览/选择状态必须按玩家隔离，不能继续用只按 world 分组的展示实体管理。
- 旧 anchor JSON 需要迁移策略：可以先忽略旧锚点，只从轨道端点生成新锚点；如需兼容，再提供一次性转换命令。
