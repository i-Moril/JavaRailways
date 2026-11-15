# 验证记录

- 分支：main。初始工作区干净，HEAD 932f7e4a；本地 origin/main 记录落后1个提交。
- 远端最新性：SSH fetch 认证失败（publickey）；HTTPS ls-remote 无可用凭据；GitHub connector 返回404。无法宣称已包含远端最新提交，用户已知此边界后批准实施。
- Core 全量测试：599项，0失败/错误/跳过。
- Basic 全量测试：499项，0失败/错误/跳过。
- BukkitPlugin 全量测试：970项，0失败/错误，1项既有实验跳过。
- Core/Basic Dokka生成通过，有既有未文档化API警告。
- git diff --check通过。
- 独立trellis-check审查公式、启动顺序、所有编辑入口、人工输入保留、分割合并与隔离模拟；未发现新增产品缺陷。
- 旧RebuiltRailMetadataTest将null derail当作有曲线限速，改为曲线fixture并独立验证直线解除CURVE。

## 数据与运行边界
新增配置项带默认1.0，不升级physics schema；世界存档结构与版本保持。自动限速派生快照在加载时重建，不累计倍率。人工CURVE/SUBGRADE保持原值，独立安全监测使用当前派生脱轨阈值；缺少authoring不猜测自动意图。不逐tick重算。
未进行真实服务器或客户端验收，不推送或部署。
