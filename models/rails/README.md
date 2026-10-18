# 轨道模型

`standard-railonly/` 是从本地 Folia 测试服复制的无路基普通钢轨包；`standard/` 和 `standard-ballastless/` 为配套合并路基配置。

这里保存可部署到 JavaRailways 轨道包目录的模型配置。配套客户端资源已合并至 `models/resourcepack/`。

- `standard/rail_model.yml`：合并沙砾路基，Custom Model Data `730001`。
- `standard-ballastless/rail_model.yml`：合并石质路基，Custom Model Data `730002`。
- 钢轨 `light_probe_offset` 为 `(0, 0.5, 0)`。
- 路基 `light_probe_offset` 为 `(0, 1.505, 0.505)`，相对于 Display 视觉原点。

资源包入口为 `models/resourcepack/assets/minecraft/items/paper.json`，模型位于 `models/resourcepack/assets/javarailways/models/rail/`。

局部轴统一为 **X 横向（上方向 × 前方向）、Y 向上、Z 沿轨向前**，与列车和信号模型一致。
`offset` 与 `light_probe_offset` 使用此基底；`rotation` 在基底之后作用于元素，`scale` 仍沿元素自身轴。
当前配置已换算偏移和元素旋转，保留原模型实际位置和尺寸；资源包网格无需改动。

旧开发配置的人工核对规则：位置和光照偏移 `(x,y,z)` 改为 `(-z,y,x)`；元素旋转在左侧组合
`Ry(-90°)`，不能对任意 XYZ 欧拉角简单减去 90°；元素自身缩放不变。上面的三个包已完成此转换。
2026-10-18 已另行完成开发服离线迁移，并更新本地 `models/rails.zip`；服务器完整恢复点与验证结果见
[迁移说明](../../scripts/model-coordinate-migration/README.md)。后续打包应使用这里的当前配置。
