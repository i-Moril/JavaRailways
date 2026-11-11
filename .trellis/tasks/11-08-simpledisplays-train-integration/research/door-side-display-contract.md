# Same/opposite-side opening display — 2026-11-10

User confirmed screen opening-side indications must follow the same reversal/orientation convention as actual door operations. Continue current task; preserve prior dirty work. No tests/server/commit/deploy: source/test-source compilation and static review only, unified acceptance later.

## Gap and boundaries

Actual door execution already swaps operating LEFT/RIGHT after committed reversal and maps operating end to each installed car via behaviorRelativeTail. Screen input currently has no door fact or per-car operating-end fact. Do not use screen pose, car number, station name, static station metadata, speed alone, or last stop to guess opening side. Do not modify train operation or station scheduling decisions.

SimpleATO adds nullable `TimetableUnitDisplayContext.doorSide: DoorSide?`, captured from exact actual entered automatic run and physically proven current stop/effective station-program side. NONE is known no-opening, null unknown. Keep independent physical arrival evidence across next-mission preparation where valid; departure/ambiguous/no scope/manual/derail invalidates. Changes to effective side/reversal participate in sampled revision/key. Explicit operation overrides must not silently show the berth default as if it were the effective side. This is intended/effective side, never proof of actual animated aperture or a future stop's opening side.

Host projects operational.doorSide as LEFT/RIGHT/BOTH/NONE or null. On the existing simulation-owner metadata sample capture car.operatingEnd=head/tail using EXACT existing TrainControlManager.operatingControlEndFor + Train.behaviorRelativeTail, same as actual platform door execution. Carry only detached value through sample cache. Include end in semantic context invalidation where needed. No worker/live getters/new scheduler/Core physical snapshot changes.

Decorations adds optional config door-side:left|right, relative to the installed car/model's fixed HEAD basis, absent=unspecified; never change config upon reversal. Additive config keeps payload schema-version1; no runtime migration. Before typed SDL draw add ctx.data.display={doorSide:installed-side|null,openingSide:this|opposite|both|none|null}. Raw LEFT/RIGHT need known car.operatingEnd; flip semantic side for tail, compare to static installation. BOTH=>both, NONE=>none regardless installation; unknown facts=>null. No hint for unknown this/opposite, no fallback to viewer locale or guessed side. Literal metadata is data, not code.

## Files and acceptance

Domain context/dispatcher/position evidence + deferred tests; host operational projection, frame/cache/WorldManager capture and tests; Decorations config/schema/typed binding helper + deferred tests. Docs record actual field semantics. No actual full screen template/glyph work this slice. Default configs without door-side remain valid. Deferred cases include opposite installed sides, both/none/unknown, reversed car in formation, real reversal retained platform side, physical departure clear, explicit stop program side override and stable captured data. Root serial compiles touched modules and test sources, no test execution. Timing TODO and15-second language rotation unchanged.
