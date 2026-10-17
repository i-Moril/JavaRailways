# Target endpoint save audit — 2026-10-17

## Source and authority

开发服为远端`user@192.168.1.230`，不是本地服务器。读取目录：
`/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96/plugins/JavaRailways/worlds`。

本轮经SSH只读读取12对current/.bak文件，在远端内存解析，只有摘要写入本地`/tmp/javarailways-endpoint-audit-20261017.json`。没有停服、触发保存、部署或改写远端，也未把本地旧副本当作当前远端。

版本、数量、引用及几何检查为Python实现；未运行新current Kotlin codec、离线转换或完整业务恢复。在线读取各文件不构成原子一致快照或持久恢复点。

## Prior migration verified

10月16日[迁移记录](../../../../09-18-signal-addon-implementation/research/dev-server-migration-2026-10-16.md)记录整目录外部备份、ownership转换、受限重复点修复及12对真实restore；后续[部署记录](../../../../09-18-signal-addon-implementation/research/dev-server-deployment-2026-10-16.md)记录配套JAR安装。

本次远端12份infrastructure及其runtime均为outer2；Basic infrastructure2、SimpleATO infrastructure1、Core infrastructure2，SimpleATO字段存在，outer revision逐对相同。旧归属转换已经完成，不能重复列作本次待办。

## UUID-only connection remains

主世界current和.bak各106 rails/234有向连接；字段仍为nextRailAtA/B、activeTrackAtA/B，值是轨道UUID字符串。以Curve.TOLERANCE=0.005检查，每条连接恰有一个共点目标端点；A-A=28、A-B=85、B-A=85、B-B=36。
所有12份infrastructure均无检查到的缺目标、重复引用、超2分支、非法active、端点歧义、非对称连接或非递增arc。其余五个世界无轨道；12份runtime的Core trains均为空。没有用本次摘要声称已检查所有业务引用或完成新格式迁移。

## Observed per-file hashes

每行同时记录infrastructure/runtime摘要；这是一次读取结果，不承诺文件随后不变。

- `2952ad2c-41ce-40bc-b83b-1b578ae7af12-infrastructure.json`: revision 63600, epoch 63630; infrastructure SHA256 `830c150c47c0e16faee2cd5ed04f3ffc5b2487e766516fde604381b6f98b341e`; runtime SHA256 `dee2201ed8fb92745835d8efdd0dbfe6c3e9c26690f3ea4eb9bca14e4ce0c856`.
- `2952ad2c-41ce-40bc-b83b-1b578ae7af12-infrastructure.json.bak`: revision 62400, epoch 62430; infrastructure SHA256 `5224384ed64750491294b61a4f72e0572bd429ebbe3646d2e82733169b109fcd`; runtime SHA256 `dfbbef0a49bf34a85420568f1ca4de9ddce2eff1393bb5e0ac0b68a3ac019b2f`.
- `48531d55-8f7b-4d23-8134-34300e91b459-infrastructure.json`: revision 63600, epoch 63632; infrastructure SHA256 `51b7f1ca09e6c9d4195414a511fb025aaed97b9134d65dd6fe0618e81a8960f3`; runtime SHA256 `5ee22c77ac2412f1bba5cde336e1479af816c58ad247a269abf0ba57a57065b7`.
- `48531d55-8f7b-4d23-8134-34300e91b459-infrastructure.json.bak`: revision 62400, epoch 62432; infrastructure SHA256 `d3ce3dc0188cbbdcfdbe70d8a90d0ec0cc3c1e4ca1ba8fb1b5a6dc31b98ab3d0`; runtime SHA256 `073b4f57857053b76a6fbf5985a853e974eab07778f4ff505778980edde21db3`.
- `6b4a8321-6116-4743-89cf-75ae3dfd24b0-infrastructure.json`: revision 4800, epoch 4837; infrastructure SHA256 `95ea534b203e58007220cb3d1255952f4337121ab3c783b5cdf60eaf04a57248`; runtime SHA256 `0704828f221e7ac6d5f0f1af708f8c5078f434c87ac0cddda774d99005d54b91`.
- `6b4a8321-6116-4743-89cf-75ae3dfd24b0-infrastructure.json.bak`: revision 3600, epoch 3637; infrastructure SHA256 `90a822ae34e71993061064e22c4d5dcbdc6a93e10c661ab4e7e28b4351f0f288`; runtime SHA256 `f5bf570bd2973279116102420b00b07fe26cefbfab081bf1aa6abc734fb43c72`.
- `9a826ffd-0e87-4341-b544-9d466ed5551e-infrastructure.json`: revision 63600, epoch 63631; infrastructure SHA256 `51b7f1ca09e6c9d4195414a511fb025aaed97b9134d65dd6fe0618e81a8960f3`; runtime SHA256 `2408b32796aa2299fdce957f54e8a00f8558a8e33e775c55b833aec16a78b6be`.
- `9a826ffd-0e87-4341-b544-9d466ed5551e-infrastructure.json.bak`: revision 62400, epoch 62431; infrastructure SHA256 `d3ce3dc0188cbbdcfdbe70d8a90d0ec0cc3c1e4ca1ba8fb1b5a6dc31b98ab3d0`; runtime SHA256 `8fab7d689e13c34a906bcf82dec2b1146071c95aaca64a6a5a495d1c0ed27f93`.
- `9bc83aa0-a554-4434-8389-7af2285366ec-infrastructure.json`: revision 4800, epoch 4843; infrastructure SHA256 `95ea534b203e58007220cb3d1255952f4337121ab3c783b5cdf60eaf04a57248`; runtime SHA256 `96f4c704fac94c33b8fe006945e0e6b207df446bd59dc6d8020e6e17e5509e0a`.
- `9bc83aa0-a554-4434-8389-7af2285366ec-infrastructure.json.bak`: revision 3600, epoch 3642; infrastructure SHA256 `90a822ae34e71993061064e22c4d5dcbdc6a93e10c661ab4e7e28b4351f0f288`; runtime SHA256 `32972f759f6795b1e91feb705a720782a1956ae4313381190f1e742baf66f058`.
- `f9c49e70-b02c-4d3b-8f70-7ed51cdf3fb0-infrastructure.json`: revision 4800, epoch 4845; infrastructure SHA256 `95ea534b203e58007220cb3d1255952f4337121ab3c783b5cdf60eaf04a57248`; runtime SHA256 `221e219696ceedfb5e14e935841d90bb650d27a615e192d85144f8bfc10f23e9`.
- `f9c49e70-b02c-4d3b-8f70-7ed51cdf3fb0-infrastructure.json.bak`: revision 3600, epoch 3644; infrastructure SHA256 `90a822ae34e71993061064e22c4d5dcbdc6a93e10c661ab4e7e28b4351f0f288`; runtime SHA256 `70d018eb64e96f53ecedfd76ee10a828816bd01664528dae13d3112a1999b4c3`.

## Scope correction

本任务只处理显式端点current形状、共享严格恢复及一次性离线端点转换；建议撤回旧规划的打包自动启动迁移和重做ownership转换。保留Core infrastructure约定版本2，其他边界不变；当前开发服文件存在不等于发布/冻结基线。

远端应用须另行授权；应用前重新停写、整目录外部恢复点和文件清单核验。本轮尚无新格式实现或迁移成功声明。

## Implementation source copy

用户批准实现后，经SSH从同一192.168.1.230实例只读取得24个世界配对文件至`/tmp/javarailways-explicit-endpoints-20261017/source/worlds`。归档`source-files.tar.gz` SHA256为`623f0d00bc72af2b62bb8b0888c47bee2e1b527c89604cf3225d91de9276bab9`。每文件hash记录于同目录上级`source-sha256.json`，配对版本与数量摘要为`baseline-pairs.json`。所有12对outer及Core/Basic/SimpleATO revision相符，各runtime内epoch相符。该在线读取副本用于离线验证，不是停服恢复点。
