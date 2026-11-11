# 取消新建列车的额外随机 UUID

## 用户确认与本批边界

前批固定单元与去库存修改已提交为 `4b6583ad`。本批取消 Bukkit 创建普通列车时为已有列车名额外生成的随机 UUID。

用户明确选择：保留稳定 `train.id`，重命名只更改显示 `train.name`。新列车以创建时给定的名称作为 ID，初始显示名称相同；后续名称、成员变化与保存恢复不重建这个 ID。默认单元继续复用已有列车 ID。已有 UUID 形态的存档身份不自动改写，不改变存档结构或版本。

行为缺口位于 `TrainCommand.submitCreate` 的 ID 分配，而非 Core 发布流、玩家实体或轨道标识。预期修改限于命令创建/展示、Core 对默认单元创建身份的只读预检，以及相应创建、冲突、重命名和保存恢复测试。

沿用现有 Core 唯一性规则：已被重联列车保留的原单元 ID 不能因来源列车容器退休而复用；冲突拒绝创建，不生成随机备用 ID。预检复用当前索引和真实组成，不新增成员、名字或库存注册表。

`publicationStreamId`、世界运行实例标识以及玩家/实体/轨道 UUID 有独立职责，本批保留。没有列车名称到全部运行引用的迁移，也不修改已部署服务器数据。

## 验证要求

使用真实不同列车名验证逐车 ID 隔离；创建后的 train/unit/car 身份一致且保存恢复保留；重命名只改变显示名；当前列车、保留单元及准备中的结构结果产生的身份冲突均拒绝且无部分创建。命令展示在 ID 与显示名相同时只显示一次。

## 实现与验证结果

实现已冻结：`TrainCommand` 的新 train ID 直接取创建名；相同名称/ID 不重复展示。
Core 提供 `validateDefaultTrainCreationIdentity` 帧间只读预检，保留构造器最终身份/组成校验。
详细机制归 [Bukkit 身份合同](../../../spec/bukkit-plugin/backend/interaction-render-persistence.md#列车稳定-id-与显示名称current)
与 [Core 组成合同](../../../spec/javarailways/core/train-composition.md)，本页不另建身份规则。

定向回归通过：Core 的 `CoreTrainConstructionTest`、`WorldPersistenceRoundTripTest`、`TrainNameTest` 共 34 项，
Bukkit 的 `TrainPlacementSelectionTest` 6 项，均零失败/错误/跳过。
构建日志 `/tmp/jr-no-extra-train-uuid-tests.log` 为 `BUILD SUCCESSFUL`；独立源码复审与 `git diff --check` 通过。
前批两项全量测试失败已单独记录，本批没有重跑全量，不以 40 项定向回归替代全量通过声明。

本批未提交、未推送、未部署，也没有改写开发服存档。
