# Consist changes and locomotive-hauled operation — planning research

## Verified existing source

- Core/src/main/java/work/art1st/javarailways/core/physics/train/TrainPersistence.kt: SavedTrain contains ordered SavedCar records; cars retain stable IDs and installed orientation.
- Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt: splitBefore splits by stable car ID, retains original prefix identity and gives suffix a new train ID, with prepared structural publication.
- Core/src/main/java/work/art1st/javarailways/core/physics/train/TrainCoupling.kt: physical collision coupling has preparation/publication and controller integration. This is not proof of an end-to-end automatic station coupling workflow.
- Basic/src/main/java/work/art1st/javarailways/basic/control/TrainDynamics.kt: traction, braking, mass and resistance aggregate per-car profiles; one train profile/handle scaling still governs the consist.
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/ato/AbstractATO.kt: prepareConsistChange updates actuator dynamics and derived braking safety. This does not establish safe Dispatcher mission, depot assignment or admission transfer.
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/depot/Depot.kt: RollingStockRecord is trainId-based with a single home depot/model package and stored carCount/length/capability summary. Assignment and stabling occupancy likewise refer to trains.

## Proposed model direction, not implementation authorization

Keep Core as authoritative owner of physical cars, orientation and consist membership. Reuse stable car identities rather than create a second conflicting physical composition store. Distinguish persistent operating assets (individual cars or explicitly grouped fixed sets), current physical consist, and duty/resource assignment. Retain constituent provenance across split/couple; train container identity alone is insufficient for inventory or home affiliation when differently owned vehicles couple. Derived length, mass, braking and capabilities must refresh against composition changes.

A future split/couple operation must define output bindings and the disposition of every resulting consist. Validate assignment transfer, occupied resources, braking/parking of detached unpowered cars and failure/publication behavior. Physical split/couple success alone cannot authorize subsequent movement. A combined consist has one active movement authority, even if its locomotive and coaches participate in different longer-term asset schedules.

Locomotive-hauled fixed consists can reuse per-car traction/brake foundations (unpowered coaches have no traction); full locomotive exchange additionally needs active cab identity/orientation, enabled and controllable power units, control compatibility, detached-vehicle securing and appropriate train-level control/brake configuration. An operating-end flip is not a physical locomotive run-around. Detailed brake-pipe propagation or other high-fidelity systems are separate scope, not implied by this analysis.

Circulation validation eventually tracks constituent assets and assembly compatibility: splitting one train into two does not create rolling stock, and coupling two does not destroy it. Current fixed-consist circulation may use intact units but must state its domain and reject unsupported structural actions rather than apply incorrect whole-train arithmetic.

## Resolved scope — 2026-10-22

User confirmed complete fixed EMU units are in current scope; arbitrary internal-car splits and locomotive-hauled operation are deferred. Storage, coupling and separation are fixed circulation events and forbidden in runtime conditional branches. Current design.md/prd.md supersede the earlier future-only discussion above. No migration or schema version change was made in this research.
