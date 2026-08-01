---
title: API 状态与兼容性
slug: /extensions/api-status
---

# API 状态与兼容性

## 当前兼容矩阵

| 项目 | 当前值 |
|---|---|
| JavaRailways 版本 | `1.0-SNAPSHOT` |
| Paper API | `1.21.11-R0.1-SNAPSHOT` |
| BukkitPlugin 运行时 Java | 21 |
| Core/Basic 字节码目标 | 17 |
| Kotlin | 2.3.0 |
| 稳定 API artifact | 尚无 |
| 二进制兼容承诺 | 尚无 |

## API Reference 收录规则

后续 Dokka 站点只收录明确列入白名单的包，不按可见性自动收录整个模块。初始候选为：

```text
work.art1st.javarailways.core.api.*
work.art1st.javarailways.basic.api.*
```

`work.art1st.javarailways.bukkit.extension.*` 当前包含平台实现和 Core 内部依赖，不作为稳定 Bukkit API。待 `work.art1st.javarailways.bukkit.api.*` 建立后再加入。

## 文档完成标准

一个声明进入稳定 API 前至少需要说明：

- 用途以及对象由谁创建、谁销毁；
- 允许调用的线程；
- 是否阻塞或属于 heavy operation；
- 参数单位、范围和失败方式；
- 返回对象是快照还是实时视图；
- 事件派发线程和异常隔离策略；
- 对持久化及插件卸载的影响；
- `@since` 和弃用替代方案。

生成 API Reference 不能替代以上内容，也不能替代扩展教程和可编译示例。
