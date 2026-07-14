---
title: 列车模型包与行为脚本
slug: /train-model/
---

# MineRails 列车模型包与行为脚本教程

:::warning 格式状态
本文描述当前源码实现。模型包格式已经带有 `config-version`，但尚未发布跨 MineRails 版本的兼容承诺；发布模型包时应同时声明测试过的 MineRails 版本。
:::

本文说明如何编写 MineRails 的列车模型包，包括：

- 模型包目录结构
- `manifest.yml`、车厢、部件、动画与动作配置
- `control.yml` 与车厢 `control` 动力性能配置
- 平台碰撞、座位、交互实体的写法
- `behavior.js` 列车行为脚本写法
- 保存策略 `follow-package` / `snapshot`

字段结构采用严格校验；Schema、编辑器配置和校验命令见 [YAML Schema 与严格校验](train-model/schema-reference.md)。

当前可参考的完整示例在：

```text
models/s100/
```

测试服运行目录通常是：

```text
BukkitPlugin/run/plugins/MineRails/trains/s100/
```

正式使用时，把一个模型包目录放到：

```text
plugins/MineRails/trains/<package-name>/
```

然后重启插件或服务器加载。

---

## 1. 模型包目录结构

推荐结构：

```text
plugins/MineRails/trains/s100/
  manifest.yml
  actions.yml          # 可选：声明式动作
  behavior.js          # 可选：列车行为脚本
  control.yml          # 必需：手柄控制/列车动力学参数
  cars/
    s100/
      head.yml
      motor.yml
      trailer.yml
      motor_tail.yml
      head_tail.yml
  parts/
    s100/
      body.yml
      door.yml
      door_light_left.yml
      floor_platform.yml
      bogie.yml
```

约定：

- `manifest.yml` 定义包名、类型、显示名称和编组。
- `cars/` 下定义车厢。
- `parts/` 下定义可复用部件。
- `actions.yml` 描述“开左门”“关右门”等业务动作。
- `behavior.js` 描述“什么时候调用哪些动作”。
- `control.yml` 和每个车厢的 `control` 块都是必需配置；当前 loader 不再用代码默认值补齐缺失字段。

`cars/s100/motor.yml` 中的 `name: motor` 会形成车厢名 `s100/motor`。在 `manifest.yml` 的 `composition` 中通常写简单名：`motor`。

---

## 2. manifest.yml

示例：

```yml
config-version: 2
name: s100
type: train
display-name: "S100 EMU"
composition:
  - head
  - motor
  - trailer
  - motor_tail
  - head_tail
```

字段：

| 字段 | 说明 |
|---|---|
| `config-version` | 配置版本。当前 manifest 必须使用 `2`，其他值会拒绝加载。 |
| `name` | 包名，也用于 `/mr train create <name>`。 |
| `type` | 必须是 `train`。 |
| `display-name` | 显示名，可选。 |
| `composition` | 编组顺序，引用 `cars/` 中的车厢简单名。 |

创建列车：

```text
/mr train create s100
```

---

## 3. 车厢配置 cars/*.yml

示例：

```yml
config-version: 1
name: motor
length: 17.8
floor-height: 1.0
connector-length-a: 0.1
connector-length-b: 0.1
control:
  mass: 37000
  rotating-mass-factor: 1.08
  motor:
    enabled: true
    max-force: 90000
    max-power: 950000
    adhesion-mass-ratio: 1.0
  brake:
    max-force: 52000
    regenerative-force: 30000
    adhesion-mass-ratio: 1.0

body-parts:
  - import: floor_platform
    offset: {x: 0, y: -1.0, z: 0}

  - import: body_seated
    offset: {x: 0, y: 1.25, z: 4.5}
    rotation: {y: 180}

  - import: door
    offset: {x: 0, y: 0.5, z: 0}

bogies:
  - offset: {x: 0, y: 0, z: 5.75}
    parts:
      - import: bogie
    wheel-diameter: 0.86
  - offset: {x: 0, y: 0, z: -5.75}
    parts:
      - import: bogie
    wheel-diameter: 0.86
```

坐标约定：

| 轴 | 含义 |
|---|---|
| `x` | 横向，左右方向 |
| `y` | 竖向，向上为正 |
| `z` | 纵向，车厢长度方向 |

常用字段：

| 字段 | 说明 |
|---|---|
| `length` | 车厢刚体长度，影响物理排布。 |
| `floor-height` | 车厢地板相对轨道曲线的高度。 |
| `connector-length-a` / `connector-length-b` | 两端连接器长度。 |
| `body-parts` | 车体部件列表，跟随车厢中心和车体姿态。 |
| `bogies` | 转向架定义，跟随各自轨道位置和方向。 |
| `bogies[].offset.z` | 转向架相对车厢中心的纵向位置。 |
| `bogies[].parts` | 转向架模型部件。 |
| `bogies[].wheel-parts` | 可选的轮对部件。 |
| `bogies[].wheel-diameter` | 轮径。 |

---

## 4. 手柄控制与动力学配置

模型包必须用 `control.yml` 定义共通手柄、制动建立时间、阻力、粘着与 jerk 限制；每节车也必须在 `cars/*.yml` 的 `control` 块中写全质量、牵引力和制动力字段。运行时会按编组自动聚合：动车提供牵引力，所有车提供质量、阻力和制动力。

`control.yml` 示例：

```yml
config-version: 1
max-speed: 33.3
controller:
  power-notches: [P1, P2, P3, P4, P5]
  brake-notches: [B1, B2, B3, B4, B5, B6, B7, EB]
traction:
  notch-scales: {P1: 0.2, P2: 0.4, P3: 0.6, P4: 0.8, P5: 1.0}
brake:
  notch-scales: {B1: 0.15, B2: 0.30, B3: 0.45, B4: 0.60, B5: 0.75, B6: 0.90, B7: 1.0, EB: 1.25}
  apply-time: 1.2
  release-time: 1.8
resistance:
  a: 900
  b: 22
  c: 5.5
adhesion:
  coefficient: 0.22
jerk:
  traction: 0.7
  brake: 1.0
```

车厢 `control` 示例：

```yml
control:
  mass: 37000
  rotating-mass-factor: 1.08
  motor:
    enabled: true
    max-force: 90000
    max-power: 950000
    adhesion-mass-ratio: 1.0
  brake:
    max-force: 52000
    regenerative-force: 30000
    adhesion-mass-ratio: 1.0
```

`power-notches` / `brake-notches` 必须非空且不能重复，`notch-scales` 的键必须与对应档位列表严格一致。`control.yml` 的所有分组和示例中的数值字段都是必填项。车厢可以额外提供 `motor.force-curve` 和覆盖全局 Davis 系数的 `resistance`。

每 tick 计算近似为：

```text
F = Σ牵引力 - Σ制动力 - ΣDavis阻力 - Σ坡度阻力
a = F / Σ有效质量
```

其中坡度阻力由每节车当前轨道方向的竖直分量估算，牵引/制动力受粘着限制，最终加速度受 jerk 限制。

手动驾驶不使用控制命令。玩家用 `/mr train key` 获取通用列车钥匙，手持钥匙坐在任意列车驾驶座上，然后直接用移动键操作手柄。

| 输入 | 效果 |
|---|---|
| `W` | 手柄上拉一档，长按会连续上拉到最大制动档。 |
| `S` | 手柄下拉一档，长按会连续下拉到最大牵引档。 |
| `D` | 手柄回中到 `N`。 |

---

## 5. 部件引用写法

### 5.1 import 引用

```yml
- import: door
  offset: {x: 0, y: 0.5, z: 6.0}
  rotation: {y: 180}
```

`import` 会在当前 namespace 下查找部件。例如车厢在 `cars/s100/` 下，则 `door` 默认解析为 `parts/s100/door.yml`。

### 5.2 跨包引用

```yml
- import: other_package/shared_part
```

如果当前包找不到，会尝试按第一个路径段查找其他已加载模型包。

### 5.3 inline 部件

简单部件也可以内联：

```yml
- config-version: 1
  name: marker
  type: dummy
  offset: {x: 0, y: 1, z: 0}
  parts:
    - import: small_light
```

---

## 6. 部件配置 parts/*.yml

所有部件都需要：

```yml
config-version: 1
name: door
type: dummy
```

当前支持的 `type`：

| type | 运行时实体 | 用途 |
|---|---|---|
| `dummy` | 无实体 | 纯分组/变换节点。 |
| `item_display` | Bukkit `ItemDisplay` | 显示资源包模型。 |
| `interact` | Bukkit `Interaction` | 点击/交互 hitbox。 |
| `seat` | 点击 `Interaction` + 零尺寸乘客 `Interaction` | 座位。 |
| `platform` | chicken-mounted invisible shulker grid | 可碰撞地板/平台。 |

`offset`、`rotation`、`parts`、`animations` 可以用于任意部件类型。

---

## 7. item_display 部件

示例：

```yml
config-version: 1
name: side_display
type: item_display
item:
  type: IRON_NUGGET
  item-model: "art1st/tc/s100/side_display"
  scale: {x: 1.0, y: 1.0, z: 1.0}
```

字段：

| 字段 | 说明 |
|---|---|
| `item.type` | Bukkit `Material`，例如 `IRON_NUGGET`。 |
| `item.item-model` | Minecraft item model component。会解析为 `minecraft:<value>`。 |
| `item.custom-model-data` | 可选，旧式 CustomModelData。 |
| `item.scale` | 显示缩放。 |

---

## 8. interact / seat / platform

### 8.1 interact

```yml
config-version: 1
name: click_box
type: interact
interaction:
  width: 1.0
  height: 2.0
```

`interact` 只负责交互 hitbox，不当座位，也不当可站立地板。

### 8.2 seat

```yml
config-version: 1
name: seat
type: seat
```

`seat` 使用一个 1×1×1 的 `Interaction` 作为点击 hitbox，并使用一个零尺寸 `Interaction` 作为实际乘客 carrier。驾驶座可以在同一个部件上添加：

```yml
config-version: 1
name: driver_seat
type: seat
interaction:
  driver: true
  control-end: head
```

`control-end` 可写 `head` / `front` / `f` 或 `tail` / `rear` / `back` / `r`，用于说明该驾驶室控制列车哪一端；省略时由当前控制逻辑决定。

玩家必须手持通用列车钥匙坐在 `driver: true` 的驾驶座上，W/S/D 手柄控制才会生效。

### 8.3 platform

```yml
config-version: 1
name: floor_platform
type: platform
platform:
  rows: 3
  columns: 18
  spacing: {x: 1.0, z: 1.0}
```

`platform` 用于可碰撞地板。当前实现会展开为多格鸡载隐形 Shulker：

```text
invisible Chicken vehicle, non-collidable
  └─ invisible Shulker passenger, collidable
```

说明：

- `rows` 沿 `x` 方向展开。
- `columns` 沿 `z` 方向展开。
- `spacing.x` / `spacing.z` 控制网格间距。
- 平台碰撞会跟随车体位置和水平旋转；Minecraft 实体 hitbox 对斜坡/倾斜面的支持有限，因此坡道只能近似。

---

## 9. 部件动画 animations

动画可以写在部件顶层：

```yml
animations:
  - name: doors_l
    keep-final-state: true
    keyframes:
      - at: 0.0
        scene: closed
        offset: {z: 0.0}
      - at: 0.3
        offset: {z: 0.1}
      - at: 1.3
        scene: open
        offset: {z: 0.625}
```

也兼容旧写法，写在 `item.animations` 内。

### 9.1 keyframes 绝对时间轴

```yml
animations:
  - name: doors_r
    keep-final-state: true
    keyframes:
      - at: 0.0
        scene: closed
        offset: {z: 0.0}
      - at: 1.3
        scene: open
        offset: {z: -0.625}
```

字段：

| 字段 | 说明 |
|---|---|
| `name` | 动画名。多个部件可使用同一动画名。 |
| `keep-final-state` | 结束后是否保持最终状态，默认 `true`。 |
| `keyframes[].at` | 绝对时间，单位秒。 |
| `keyframes[].scene` | 语义场景，例如 `closed` / `open`。`c` 会归一化为 `closed`，`o` 为 `open`。 |
| `offset` | 相对部件原始 local position 的偏移。 |
| `rotation` | 相对部件原始 local rotation 的欧拉角，单位度。 |
| `scale` | 动画缩放。 |
| `item-type` | 动画中切换 item material。 |
| `item-model` | 动画中切换 item model。 |
| `custom-model-data` | 动画中切换 CustomModelData。 |
| `brightness` | 动画中切换亮度，格式 `{block: 15, sky: 0}`。未声明时不会覆盖原亮度。 |

触发 scene：

```text
/mr train anim doors_l open
/mr train anim doors_l closed
```

也可以：

```text
/mr train anim doors_l:open
```

### 9.2 commands 相对时间写法

```yml
animations:
  - name: blink
    commands:
      - duration: 0.2
        item-model: "art1st/tc/s100/door_light_warning"
        brightness: {block: 15, sky: 0}
      - duration: 0.2
        item-model: "art1st/tc/s100/door_light"
```

`commands[].duration` 是从上一个状态到该状态的相对时间，单位秒。

---

## 10. actions.yml：声明式列车动作

`actions.yml` 用于把多个低层动画和声音组合成业务动作。

示例：

```yml
config-version: 1
actions:
  door-left-open:
    animations:
      - name: doors_l_light
        at: 0.0
      - name: doors_l
        scene: open
        at: 0.0
    sounds:
      - key: minecraft:block.iron_door.open
        volume: 0.8
        pitch: 1.0
        at: 0.0

  door-left-close:
    animations:
      - name: doors_l_light
        at: 0.0
      - name: doors_l
        scene: closed
        at: 0.6
    sounds:
      - key: minecraft:block.iron_door.close
        volume: 0.8
        pitch: 1.0
        at: 0.6
```

字段：

| 字段 | 说明 |
|---|---|
| `config-version` | Actions 格式版本，当前必须为 `1`。 |
| `actions.<name>` | 动作名。 |
| `actions.<name>.display-name` | 控制界面显示名，默认使用动作名。 |
| `actions.<name>.executable` | 是否直接显示为可执行动作，默认 `false`。 |
| `actions.<name>.requires-control` | 执行动作是否要求玩家持有列车控制权，默认 `false`。 |
| `animations[].name` | 要触发的动画名。 |
| `animations[].scene` | 可选目标 scene。 |
| `animations[].at` | 延迟执行时间，单位秒。 |
| `sounds[].key` | Bukkit/Minecraft sound key。 |
| `sounds[].volume` | 音量，默认 `1.0`。 |
| `sounds[].pitch` | 音高，默认 `1.0`。 |
| `sounds[].at` | 延迟播放时间，单位秒。 |

调试命令：

```text
/mr train action door-left-open
/mr train action door-left-close
```

建议：

- 外部逻辑和 JS behavior 尽量调用 action，而不是直接调用底层动画名。
- 动画名描述资源效果，例如 `doors_l`、`doors_l_light`。
- action 名描述业务动作，例如 `door-left-open`。

---

## 11. behavior.js：列车 JS 行为脚本

`behavior.js` 用于描述“什么时候做什么”。它不直接写模型资源，而是调用 `actions.yml` 里的动作。

示例：

```js
function forDoorSide(door_side, callback) {
  const side = String(door_side || "left").toLowerCase()
  if (side === "both" || side === "all") {
    callback("left")
    callback("right")
  } else {
    callback(side)
  }
}

function onStation(time, door_side) {
  const t = Number(time)
  forDoorSide(door_side, function(side) {
    if (t === 0) action(`door-${side}-open`)
    if (t >= 20) action(`door-${side}-close`)
  })
}

function door(side, scene) {
  const suffix = String(scene || "open").toLowerCase().startsWith("close") ? "close" : "open"
  forDoorSide(side, function(doorSide) {
    action(`door-${doorSide}-${suffix}`)
  })
}

function horn() {
  sound("minecraft:entity.minecart.riding", 0.8, 1.0)
}
```

### 11.1 可用全局 API

MineRails 会向脚本注入以下全局函数：

```js
action(name)
sound(key, volume, pitch)
log(message)
operatingEnd()
defineAction(name, options, handler)
```

| API | 说明 |
|---|---|
| `action(name)` | 调用 `actions.yml` 中的动作，返回是否成功触发。 |
| `sound(key, volume, pitch)` | 在列车附近播放声音。`volume` / `pitch` 可省略，默认 `1.0`。 |
| `log(message)` | 写入插件日志。 |
| `operatingEnd()` | 返回当前操纵端：`"head"` 或 `"tail"`。可用于映射语义上的左右侧和头尾灯。 |
| `defineAction(name, options, handler)` | 注册控制界面可发现的行为动作。`options` 支持 `displayName`、`executable`、`requiresControl`。也可以把第二个参数直接写成显示名或 handler。 |

`defineAction` 示例：

```js
defineAction("door-left-open", {
  displayName: "左侧开门",
  executable: true,
  requiresControl: false
}, function() {
  action(operatingEnd() === "tail" ? "door-right-open" : "door-left-open")
})
```

编辑器类型声明位于 `types/train-behavior.d.ts`。模型包源码可以加入：

```js
// @ts-check
/// <reference path="../../types/train-behavior.d.ts" />
```

仓库会通过 `npm run check-dsl-types` 检查 `models/s100/behavior.js`。类型检查用于发现 API 名称和参数错误，不能替代 GraalJS 运行时测试。

脚本运行在受限 GraalJS 环境中：

- ECMAScript 2023。
- 不允许访问 Java class。
- 不允许 IO。
- 不允许创建线程或进程。
- 只应该通过上面的 API 与 MineRails 交互。

### 11.2 生命周期与调用 behavior

当前只有 `onReverse(trainId)` 会在列车换向事件后自动调用。`onStation(...)` 只是推荐的自定义方法名，时刻表尚未自动调用它；目前需要调试命令或其他代码显式调用。

调试命令：

```text
/mr train behavior <method> [args...]
```

示例：

```text
/mr train behavior onStation 0 left
/mr train behavior onStation 20 left
/mr train behavior door both open
/mr train behavior horn
```

命令参数会自动转换：

| 输入 | JS 中类型 |
|---|---|
| `true` / `false` | boolean |
| `null` | null |
| `123` | number |
| `12.5` | number |
| 其他 | string |

### 11.3 推荐职责划分

推荐分层：

```text
part animations: 资源级效果，例如门移动、灯闪烁
          ↓
actions.yml:     业务动作，例如 door-left-open
          ↓
behavior.js:     行为逻辑，例如 onStation 到站开关门
```

这样模型资源、动作组合和列车逻辑可以独立维护。

---

## 12. 保存策略：follow-package 与 snapshot

全局配置：

```yml
train-model-update-policy: follow-package
```

可选值：

| 值 | 行为 |
|---|---|
| `follow-package` | 存档只保存车厢模型名。重启后从当前模型包重新构建，已有列车会跟随模型包更新。 |
| `snapshot` | 保存 resolved 配置树的 hash。重启后从全局 snapshot pool 恢复，已有列车不受模型包更新影响。 |

`snapshot` 模式会把模型快照保存到：

```text
plugins/MineRails/model-snapshots/<sha256>.json
```

相同配置树只保存一次，车辆只记录 `modelSnapshotHash`。

开发调试模型时推荐：

```yml
train-model-update-policy: follow-package
```

发布稳定车辆后，如果希望已有列车保持原样，可以改用：

```yml
train-model-update-policy: snapshot
```

---

## 13. 常用验证命令

```text
/mr train create s100
/mr train anim doors_l open
/mr train anim doors_l closed
/mr train anim doors_r open
/mr train anim doors_r closed
/mr train action door-left-open
/mr train action door-left-close
/mr train behavior onStation 0 left
/mr train behavior onStation 20 left
/mr train delete
```

建议每次修改模型包后至少检查：

1. 服务器启动时没有模型包加载错误。
2. `/mr train create <package>` 能创建完整列车。
3. 车体、转向架、门、灯、座位、平台位置正确。
4. 动画能开关并保持 scene。
5. action 能同时触发动画、灯光和声音。
6. behavior 能调用 action。
7. 保存/重启后模型和 held scene 能恢复。

---

## 14. 编写建议

- 先用 `dummy` 搭层级，再把实际显示模型挂到 dummy 下。
- 多个门页可以共享同一个动画名；触发一次会递归分发给所有匹配部件。
- `scene` 表示稳定语义状态，适合持久化；不要把临时进度当成 scene。
- `actions.yml` 是对外稳定接口；底层动画名可以随资源调整。
- `behavior.js` 只写逻辑，不直接硬编码每个 display 的资源细节。
- 大面积地板使用一个 compact `platform` 网格，不要在 car YAML 里手写几十个 shulker 平台。
- 调试模型时使用 `follow-package`；需要冻结已有列车时使用 `snapshot`。
