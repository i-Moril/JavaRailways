# 本批验收证据索引

当前状态：实现和最终自动门禁完成；独立全范围报告见[check-result](check-result.md)。当前27源a5095090，8模块2411项、前端108项、API/边界/jar/Dokka/docs通过；产品尚待本地提交。上一批2395项不作为本批证明。逐文件/全部357XML与新增16case见[final-candidate](final-candidate.json)，执行信息见[validation](validation.md)。

- [x] S1：每LIVE/SIM自有SignalLogicRuntime的构造、复用和关闭路径；catalog.rules仅中立只读输入。保留fresh realm和单World同步。
- [x] S2 LIVE-LIVE：实际logic monitor/求值路径暂停A，B完成真实physics与正式四灯结果；同A有序。
- [x] S2 LIVE-SIM：同样真实阻塞/推进；A关闭/失败不破坏B。
- [x] S2 SIM-SIM：同一prepared输入的两target自有状态；阻塞/关闭target-only。
- [x] S3：manager目录锁不包初始化/编译、host owner等待或close；A安装/编辑/关闭暂停时B仍进行对应真实工作。
- [x] 生命周期：install reservation、remove/同UUID reinstall、迟到编译/失败、catalog就绪竞态及atomic source旧epoch合同保留。
- [x] 正式规则：真实loader、分发四灯JS、target topology重新编译；混合长度/短区间/静态速度的LIVE与SIM对照、占用停车及释放后的真实输出。
- [x] 完整安装：区间occupancy、protection selection、route planning、两类inspection、resource provider/contributor、Basic claim/准备/尾释放、admission更新全部真实同target接通。
- [x] 方向与保护：固定进路/当前入口选择、准备未ready与ready、AB/BA及折返/方向约束、静态速度输入；无simulation专属业务算法。
- [x] 不可用规则：缺catalog/未知type/坏JS准备拒绝或既有Unavailable/0语义；原cause及target UUID诊断，无Bukkit模型或副作用。
- [x] 公开API：仅.api值/接口，无CoreWorld/domain实现暴露；owner preparer登记/注销、回调共享锁外；required Feature缺失仍拒绝。
- [x] 所有新注册都有close；partial install逆序清理、目标真终止后释放仍成立，另一个World资源不被关闭。
- [x] 最终源freeze→独立全范围check→受影响模块test/check、Javaconsumer/moduleboundary/jar、Dokka/docs门禁；记录实际XML与候选SHA。

Paper/Folia/客户端现场验证不由本地自动测试替代，车厢行为属于子任务4；父任务最终联合验收仍开放。

## 实际证据分层

- 主要新增信号集成全部位于 `SimpleSignalsWorldRuntimeManagerTest`，实际运行loader/分发四灯JS、真实Core LIVE/SIM、Basic准入和目标SignalEpochRuntime。LIVE-LIVE、LIVE-SIM、SIM-SIM三组参数case以最终XML名称为准，不用对象不同代替推进证明。
- 新增matrix/脱离LIVE集成使用500/300/150/500m混合区间及100km/h静态元数据，验证正式proceed/slow/caution/stop、占用与释放；极短10m链由原有manager参数化fixture合成不可变frame检验，静态速度多值/缺值及缓存重建由既有scan-plan/whole-block-max/JrFourLight单测覆盖。没有宣称新增SIM全速度参数穷举。
- 真实protection signal/inspection反馈、admission claim/尾释放/方向更新和同grant折返分别为新增独立case；实际安装复用 `FixedBlockDomainResourcePolicy`，没有simulation版业务算法。
- API登记/owner禁用/服务关闭、preparer阻塞时其他源可准备、active inspection退休及另一Feature保留均在最终XML真实执行。缺Feature仍是capture准备失败。
- 未知type、坏JS等安全语义结合原有正式编译/logic测试及LIVE/SIM共用算法核对；具体方法与覆盖层次由独立报告逐项列明，不把每一条都称为新增SIM端到端测试。
- 同UUID迟到诊断、目录等待、退休异常及引擎close隔离通过新增真实路径回归，9项具体问题已修复，详见[findings](check-findings.md)。

这里的勾选仅对应本子任务本地验收。Paper/Folia、Bukkit classloader现场、客户端与车厢脚本不在本次实测范围；最终全Feature由父任务剩余批次联合验收。
