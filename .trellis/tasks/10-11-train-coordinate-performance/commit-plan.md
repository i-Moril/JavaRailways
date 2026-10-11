# Proposed commit batch

1. `fix(core): enforce rigid train connector geometry`

Files:
- Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt
- Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt
- Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreTrainRigidGeometryTest.kt
- Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreTrainPersistedPlacementTest.kt
- Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreTrainAsymmetricTurnoutTest.kt
- Core/src/test/java/work/art1st/javarailways/core/physics/tick/PhysicsCapacityStressTest.kt
- .trellis/spec/javarailways/core/train-physics.md
- .trellis/spec/javarailways/core/persistence.md
- .trellis/tasks/10-09-stage2-code-review/implement.md
- .trellis/tasks/10-09-stage2-code-review/research/issues.md
- .trellis/tasks/10-11-train-coordinate-performance/ (PRD, design, manifests, review, raw measurements and comparison)

Excluded pre-existing work:
- TODO.md
- docs-site (submodule)
- .trellis/tasks/09-24-explicit-rail-endpoint-connections/
- ai_tasks/current_signal_rules_research_handoff.md
- models/rails.zip and models/rails/
- models/resourcepack.zip and models/resourcepack/assets/javarailways/
- models/resourcepack/assets/minecraft/items/paper.json
- models/s100.zip

After approval of this batch: commit these files only, archive only this task, repair its two cross-task links to the archive location, then record the session journal. No push or deployment.
