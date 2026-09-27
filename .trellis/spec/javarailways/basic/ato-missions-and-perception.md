# Basic route/perception snapshot 契约

## 状态与 owner

Basic只拥有自动行车和资源校验共用的不可变`RouteSnapshot`、`PerceptionSnapshot`与
`RevisionedCommandQueue`。`AtoMissionRequest`、mission resolver、mission route progress以及mission
submission属于SimpleATO，见[SimpleATO ATO policy runtime](../simpleato/ato-policy-runtime.md)。Basic不得重新建立
mission mirror、resolver facade或SimpleATO forwarding accessor。

## Snapshot机制

`RouteSnapshot(routeId,revision,topologyRevision,segments)`把一次已选路线绑定到独立的route revision和Core topology
revision；两者都是非负runtime计数，不能互相推导。segment使用rail UUID、`ViewDirection`和view-local SI弧长，满足
finite且`0 <= start <= end`。

`PerceptionSnapshot(epoch,train,route,observations)`防御性复制输入；observations按`routeDistanceMeters`非降序，
`relativeSpeedMetersPerSecond`为nullable finite值。transition固定为`ENTER/UPDATE/EXIT/PASS`。SimpleATO负责从同一
topology root与physics publication构造这些值，Basic资源校验只消费，不选择运营路线。

`RevisionedCommandQueue`只提供严格递增revision、原子enqueue和physics boundary drain；payload不可变性由owner保证。
它不解释mission、provider或constraint。

## 失败与测试

- topology/route revision混用、未排序observation、非法弧长在构造边界拒绝；
- snapshot collection不可被调用方后续修改；
- Basic不得在snapshot失败时回退读取live world并继续授权。

```bash
./gradlew :Basic:test --tests '*PhaseAFoundationTest'
./gradlew :SimpleATO:test --tests '*AtoMissionTest' --tests '*SimpleATOTest'
```
