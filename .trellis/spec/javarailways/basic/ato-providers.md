# Basic generic RailObjectStore 契约

## 状态与 owner

本页文件名保留用于迁移期导航，但Basic只拥有world-local通用`RailObjectStore`。旧`AtoProvider`、factory/registry、
boundary和controller切换策略已经删除；信号扩展使用SimpleATO inspection/restriction合同，见
[SimpleATO ATO policy runtime](../simpleato/ato-policy-runtime.md)。

## Generic lifecycle机制

通用store位于`work.art1st.javarailways.basic.api.rail`，不依赖ATO/provider合同；owner是`BasicWorldData.railObjects`，SimpleATO `StationStore`复用同一实例。

store按`RailObjectRef`和rail UUID维护两个索引，并在注册时保存rail身份。register只接受`ACTIVE`对象并拒绝重复ref；unregister不识别任何具体类型，
要求对象owner已经把lifecycle发布为`REMOVED`，然后在同一mutation lock内从两个索引删除。所有索引写入由store独占并串行执行，不要求调用方维护内部索引。

register在任何索引写入前读取外部ref、rail身份和lifecycle，外部getter失败时不留下部分注册。标准JVM容器写入不包装宽泛Throwable回滚。
unregister使用注册时捕获的rail身份；保留缺失索引和实际删除失败的诊断，不在同一锁内预先完整扫描成员后再次删除。

这使Station berth以及未来插件对象遵守同一生命周期，同时避免Basic因新增具体运营对象持续增长类型分支。

## 测试

```bash
./gradlew :Basic:test --tests '*RailObjectStoreTest'
./gradlew :SimpleATO:test --tests '*SimpleATOTest'
```
