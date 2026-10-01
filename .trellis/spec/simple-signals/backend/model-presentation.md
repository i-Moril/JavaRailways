# 信号模型绑定与更新

## 1. 状态、范围、触发条件与 owner

`TARGET / DESIGN DRAFT`，没有 renderer 实现。SimpleSignals 拥有模型包、物理摆放、variant 映射与
handle 生命周期；宿主中立能力按[通用模型 API 草图](../../bukkit-plugin/backend/model-presentation-api.md)
审查后实现。本文不把 SimpleATOBukkit 变成信号呈现桥，也不改变资源/规则 owner。

## 2. 模块工作方式

加载/建造时配置 `device -> model package + rail position + local placement + variant mapping`，
验证后解析轨道几何并创建 handle。现有信号 runtime 发布完整安全状态后，把确实改变的可见值
交给 handle；宿主异步收敛画面。灯色/速度仍由规则产生，宿主只看位置与动画场景。

逻辑信号入口和模型实际位置不同：预告机可放远处，平台信号可有局部偏移。现有 fixed-block /
protection signal 没有物理摆放字段；route indicator 已有 rail position，但也缺模型绑定，不能
假装这些字段已实现。

## 3. 内部机制与关键算法

- 摆放使用 rail UUID、canonical AB metres 和 facing；模型包拥有局部偏移/姿态策略。
  低频取得 immutable curve 并采样，缓存世界 transform。相关轨道/摆放编辑使它失效并重新解析。
- opaque `modelVariant` 映射为完整 `animationName -> sceneName` 选择，例如模型包中的
  `lamp -> green`；宿主不理解 G/YG/Y/R，也不能从 scene 反推出速度。
- 在 `SectionOccupancyRuntime` 的 coherent latest publication 后发出纯值通知；不在提交前把
  部分 selection/signals/indicators 交给 renderer，也不在 safety callback 访问 Bukkit。
- signal 使用已有 changed IDs；indicator 使用已有 entry reverse index 找候选。再比较实际
  variant/transform：只改速度或选择元数据而画面相同则不提交，不扫描全部 devices。
- definition 增删改、初始加载、reload/unload 同样驱动呈现 owner；不是只等列车进入/离开事件。
  旧 runtime 的通知只允许写自己的 handles，binding 替换先关闭旧 handle，不得污染新对象。

## 4. 接口、数据与单位

以下为内部流程伪代码，不是公开签名或新增持久化 DTO：

```text
prepare(binding, modelPackage):
    validate rail position / model references / complete variant mappings
    curve = host.railTargets.findCurve(worldId, railId)
    point = curve.getPointAt(canonicalAbMetres)
    transform = applyConfiguredPlacement(point, facing, localPlacement)
    return resolved binding + immutable model definition + transform

onCoherentPublication(published, candidateDeviceIds):
    for each bound candidate:
        state = published.stateFor(candidate)
        scenes = binding.resolveVariantOrConfiguredUnavailable(state)
        if scenes and transform equal last submitted visible value: continue
        handle.submit(next visual revision, transform, scenes)

onRemoveOrUnload(binding):
    binding.handle.close()
```

正常状态使用规则的 variant；`Unavailable` 没有虚构 variant，模型绑定必须显式配置不可用时的
画面策略。具体策略/包格式下一批审查；不能硬编码 R，也不能默认为最后一个放行画面。
绑定可以是可选呈现：未配置模型的后端信号仍工作；一旦配置绑定，则不允许缺资源引用后静默伪装成功。

## 5. 不变量、生命周期、线程与版本

呈现不能提高/降低安全授权，不能阻塞信号 epoch。模型加载/解析是 worker 低频工作；后续 owner
安装必须确认其 binding/world 仍有效。model handle 的显示 revision 不写存档、不参与资源身份。
插件正常卸载仍经过既有 Basic owner gate；gate 成功后清理显示，强制停用按既有安全行为处理。
资源 cleanup 与实体 cleanup 不是同一个操作。

## 6. 校验与失败

缺 rail/越界位置/未知模型/未知正常 variant mapping：拒绝该绑定，保留可诊断原因；既有安全规则
不因此获得放行。规则 unavailable 走显式显示策略。backend/owner 调度失败只报呈现故障并清理部分
实体，不从 safety callback 回抛。服务器不声称验证客户端资源包是否已安装。

## 7. 性能与退化

准备：每个绑定一次 ID 查找、`O(log N)` 弧长采样和模型解析；不按 tick 重定位全部信号。
发布：`O(C + M)`，C 是已有变化/反向索引命中的候选数，M 是这些候选实际比较的场景映射大小；
真正显示工作仅针对改变的模型。宿主 per-handle latest-only 允许合并旧画面，不合并安全事件。
慢渲染只让画面滞后；加载和大量建造的实际成本必须测试，不能仅靠队列有界声称无卡顿。

## 8. Good / Base / Bad

- Good：当前进路变化得到不同 variant，映射到真实 multipart 资源模型；预告机共享逻辑源但独立摆放。
- Base：限速改变但 variant 未变，控制立即读新限制，实体不更新。
- Bad：definition 已替换而旧 callback 才完成；旧 binding 关闭，旧通知不能覆盖新模型。

## 9. 必需测试

覆盖初始状态、variant 改变、速度-only no-op、indicator 元数据-only no-op、不可用显示策略、绑定
增删改、旧 callback、unload/reload 和卸载清理；断言安全输出不受呈现失败影响。模型映射/AB-BA
摆放用纯值测试，公开 host service 用独立 Java consumer 验证，真实渲染做 Folia smoke。

## 10. 未决事项与 Wrong / Correct

本批不修改世界 sidecar/schema。新增可选绑定时先审查默认值与实际独立持久化边界，不为未完成
实现连升版本，也不为开发中草图增加兼容 reader。包配置、不可用策略、倾斜/偏移与交互建造字段
下一批确定；普通物品单灯占位不能当作完整模型系统。

- Wrong：每 tick 遍历全世界信号，硬编码 modelVariant 与颜色，或让 SimpleATOBukkit 转发显示。
- Correct：SimpleSignals 解释自己的映射，只提交变更纯值；宿主只负责中立模型构造和目标线程应用。
