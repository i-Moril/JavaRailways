# 通用扩展包资源

CURRENT：manifest v2 可选 `extensions`，每个 namespaced type 指定 `directory` 与默认 1 的 `schema-version`；仅 train 包接受。宿主不理解 displays.yml/XML/defaults，资源语义归 provider。

PackageLoader 捕获显式目录下所有普通 UTF-8 文本为排序、不可变 path→source map，路径相对声明目录。拒绝绝对/drive/backslash/NUL/空/dot/parent segment、非法 UTF-8、断链、包外 symlink、目录循环及不支持文件。未声明等于空资源；已声明损坏必须报错。没有 provider 也捕获资源，不能等安装扩展后再从变化的磁盘补齐旧快照。

`LoadedTrainPackage.extensions`、`SavedTrainPackageSnapshot.extensions` 使用通用 type ID→resources。主包及每车真实来源包分别捕获；已有资源版本不读当前磁盘。Store 重算 hash 包括配置/资源；排序无关、内容变化影响 hash；空扩展省略以固定既有无扩展 hash。manifest 2/snapshot 1 保持不变，扩展独立 payload boundary 使用稳定 ID 和自身 schema-version。

宿主只捕获/验证原文，不编译 XML/JS，不创建绘制资源；安装 Decorations/SimpleDisplays 时，扩展自己的 provider 在后续校验中编译模板。显示专属目录布局与校验见 [Decorations](../../decorations/backend/index.md)。固定无扩展 fixture、篡改 hash、混合来源、失败 reload 以及路径/编码负例必须覆盖。
