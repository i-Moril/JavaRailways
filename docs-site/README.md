# MineRails 文档站点

文档正文位于仓库根目录的 `docs/`，本目录只保存 Docusaurus 构建配置。

```bash
cd docs-site
npm ci
npm run check
npm run serve

# 日常写作可使用热更新，但 Pagefind 搜索只在生产构建后生成：
npm start
```

`npm start` 和 `npm run serve` 默认监听 `0.0.0.0:32000`。本机访问 `http://localhost:32000`；其他设备可访问 `http://<服务器地址>:32000`。请只在可信网络开放该端口。

生产构建可通过环境变量设置站点地址：

```bash
DOCS_URL=https://docs.example.com DOCS_BASE_URL=/ npm run build
```

搜索使用完全静态的 Pagefind。`npm run build` 会依次：

1. 生成 Core/Basic 的 Dokka HTML API Reference；
2. 检查 JS DSL 类型和模型 YAML Schema；
3. 复制 Schema、`.d.ts` 和 API Reference；
4. 构建 Docusaurus；
5. 把中文搜索索引写入 `build/pagefind/`。

当前只要求本地构建；未来部署到 GitHub Pages 时，通过 `DOCS_URL` 和 `DOCS_BASE_URL` 设置仓库对应的站点地址与子路径。
