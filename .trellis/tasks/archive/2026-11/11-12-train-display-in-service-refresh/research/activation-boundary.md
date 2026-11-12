# 测试服激活边界（原方案；现已获批执行）

根目录 `/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`。

## 已执行
- 仅三份显示 XML，备份后原位写入并 fsync、hash 校验。路径与前后哈希在 `server-xml-update.json`。
- 没有替换 JAR、重载插件、重启、重编译线上脚本或修改存档。
- `JavaRailwaysResources` master/abe01e7 没有 XML，不改客户端资源或未跟踪解压目录；仓库 XML owner 为主仓 examples/models。

## 后续必须先征得用户许可
1. 正常 stop/save 后做恢复快照，替换已验证的 JavaRailways.jar。Decorations/SimpleDisplays 无源码/JAR改动。
2. `plugins/JavaRailways/timetables/new-timetable.js` 的16份 runSegment 参数缺少新字段，需要从权威作者文档重新生成。
3. `plugins/JavaRailways/worlds/2952ad2c-41ce-40bc-b83b-1b578ae7af12-runtime.json` 中的 `simpleAtoSnapshotJson` 内嵌时刻表定义有16个运行段，均缺 isInService。新严格 DTO 不能直接读取它。
4. 权威作者文档 `plugins/JavaRailways/webui/drafts/editor-documents/world/new-timetable-9.json` 仍有运行线 type；已逐一核对，旧脚本及 runtime 的16个 runId 均准确匹配其中13个PASSENGER、3个DEADHEAD。可据此一次性补入准确布尔并审计“除字段外无变化”，不改作者文档或其他世界/物理数据；当前没有执行。
5. 服务器配置为 `train-model-update-policy: follow-package`。Decorations 外置样式在enable时捕获，重启/重载后才使用新内屏；列车包XML需正常资源重载更新已存在实例的captured资源。复用 `/jr resources reload`，不改模型包策略，不手工修改内容寻址snapshot。
6. 启动后分别检查恢复、字段投影、资源重载、错误日志与真实客户端。插件enabled和测试通过均不代替画面验收。

不要为绕过以上步骤给新字段默认true，不自动加载旧开发形状；当前版本保持。
独立 docs-site 子模块的类型镜像不在本轮修改范围，未推进gitlink。主仓当前脚本声明已同步。

## 2026-11-12 后续执行

用户明确批准后已按此范围完成部署。完整恢复点、数据保持证明、离线工具装配问题与实际健康证据见 [部署记录](./deployment-20261112.md)；客户端视觉验收仍待用户确认。以上“未执行”描述保留为原批准前方案记录。
