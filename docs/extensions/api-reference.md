---
title: API Reference 构建
slug: /extensions/api-reference
---

# API Reference 构建

MineRails 使用 Dokka 2.2 同时读取 Kotlin KDoc 和 JavaDoc。目前只收录：

```text
work.art1st.mineRails.core.api.*
work.art1st.mineRails.basic.api.*
```

Bukkit 平台尚未建立稳定的 `bukkit.api`，因此不会把 `bukkit.extension` 或其他实现包发布为扩展 API。

## 生成网页

```bash
./gradlew dokkaGeneratePublicationHtml
```

输出位于：

```text
build/dokka/html/index.html
```

在 `docs-site/` 运行 `npm run build` 会自动生成并复制到静态站点的：

```text
build/api/current/index.html
```

站点导航栏的 **API Reference** 会打开该页面。

如果已知 GitHub 源码根地址，可生成带行号的源码链接：

```bash
DOKKA_SOURCE_URL=https://github.com/<owner>/<repo>/blob/main \
  ./gradlew dokkaGeneratePublicationHtml
```

不设置该变量时，本地 API Reference 仍可完整生成，只是不显示远程源码链接。

## 生成 Javadoc jar

```bash
./gradlew apiJavadocJars
```

输出：

```text
Core/build/libs/Core-1.0-SNAPSHOT-javadoc.jar
Basic/build/libs/Basic-1.0-SNAPSHOT-javadoc.jar
```

这些 jar 目前只是本地构建产物，尚未发布到 Maven 仓库。

## 文档债务

Dokka 已启用 `reportUndocumented`，但当前 `failOnWarning` 为 `false`，因为 Core 候选 API 仍有大量声明缺少 KDoc。后续应逐包补齐线程、单位、异常、所有权和兼容性说明，再逐步把公开 API 的警告提升为构建失败。
