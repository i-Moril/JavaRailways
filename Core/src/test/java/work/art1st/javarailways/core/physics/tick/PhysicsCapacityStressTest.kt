package work.art1st.javarailways.core.physics.tick

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.geometryBreakpoints
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Opt-in Core/headless capacity suite. Run through `:Core:physicsStressTest`; normal unit tests
 * exclude this tag. The measured epoch includes controller work, publication, frame processors,
 * and swept-occupancy rebuild, but excludes Paper/Folia rendering and network work.
 */
@Tag("physics-stress")
class PhysicsCapacityStressTest {
    @Test
    fun `measure headless physics capacity`() {
        val config = StressConfig.fromSystemProperties()
        val environment = StressEnvironment.capture()
        val results = mutableListOf<StressRunResult>()

        config.workerThreads.forEach { workers ->
            config.scenarios.forEach { scenario ->
                config.trainCounts.forEach { trainCount ->
                    repeat(config.repetitions) { repetition ->
                        results += runScenario(config, scenario, trainCount, workers, repetition + 1)
                    }
                }
            }
        }

        val report = StressReport(environment, config.toSnapshot(), results)
        writeReports(report, config.reportDirectory)
        enforceRequestedCapacity(config, results)
    }

    private fun runScenario(
        config: StressConfig,
        scenario: StressScenario,
        trainCount: Int,
        workerThreads: Int,
        repetition: Int
    ): StressRunResult {
        val coordinator = PhysicsCoordinator(
            workerThreads = workerThreads,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        try {
            val world = CoreWorld.createEmpty(DummyTickManager, coordinator)
            val controllers = createScenario(world, config, scenario, trainCount)
            val expectedEpochs = config.warmupEpochs + config.measurementEpochs
            val samples = ArrayList<PhysicsEpochMetrics>(config.measurementEpochs)

            coordinator.start()
            repeat(config.warmupEpochs) {
                coordinator.stepSimulation(LOGICAL_DELTA_MILLIS).await()
            }
            val warmupDerailments = controllers.mapNotNull { controller ->
                controller.controlledTrain.derailReason?.let { reason ->
                    "${controller.controlledTrain.id}: $reason"
                }
            }
            check(warmupDerailments.isEmpty()) {
                "stress scenario derailed during warmup: ${warmupDerailments.joinToString()}"
            }

            var expectedEpoch = config.warmupEpochs.toLong() + 1L
            repeat(config.measurementEpochs) {
                val metrics = coordinator.stepSimulation(LOGICAL_DELTA_MILLIS).await()
                assertEquals(expectedEpoch++, metrics.epoch)
                assertEquals(PhysicsWakeSource.SIMULATION, metrics.source)
                assertEquals(trainCount, metrics.activeTrainCount)
                assertEquals(trainCount, metrics.totalTrainCount)
                assertFalse(metrics.deltaClamped)
                assertEquals(0.0, metrics.droppedDeltaMillis, 0.0)
                assertEquals(0, metrics.collisionClaims)
                samples += metrics
            }

            controllers.forEach { controller ->
                assertEquals(expectedEpochs.toLong(), controller.tickCount)
                assertFalse(controller.controlledTrain.isDerailed)
            }
            val mailbox = world.trainFrameMailboxMetrics()
            assertEquals(0L, mailbox.droppedCapacityFrames)
            assertEquals(0L, mailbox.droppedStaleFrames)
            if (scenario == StressScenario.SHARED && trainCount > 1) {
                assertTrue(
                    samples.any { it.candidateChecks > 0L },
                    "shared scenario did not exercise swept-occupancy candidate checks; reduce spacing"
                )
            }

            val wall = samples.map(PhysicsEpochMetrics::wallNanos)
            val workerMax = samples.map(PhysicsEpochMetrics::workerMaxNanos)
            val workerTotal = samples.map(PhysicsEpochMetrics::workerTotalNanos)
            val coordinatorTimes = samples.map(PhysicsEpochMetrics::coordinatorNanos)
            val p95Millis = percentileNanos(wall, 0.95) / NANOS_PER_MILLI
            return StressRunResult(
                scenario = scenario.id,
                trainCount = trainCount,
                carsPerTrain = config.carCount,
                workerThreads = workerThreads,
                repetition = repetition,
                warmupEpochs = config.warmupEpochs,
                measurementEpochs = config.measurementEpochs,
                wallP50Millis = percentileNanos(wall, 0.50) / NANOS_PER_MILLI,
                wallP95Millis = p95Millis,
                wallP99Millis = percentileNanos(wall, 0.99) / NANOS_PER_MILLI,
                wallMaxMillis = wall.max() / NANOS_PER_MILLI,
                workerMaxP95Millis = percentileNanos(workerMax, 0.95) / NANOS_PER_MILLI,
                workerTotalP95Millis = percentileNanos(workerTotal, 0.95) / NANOS_PER_MILLI,
                coordinatorP95Millis = percentileNanos(coordinatorTimes, 0.95) / NANOS_PER_MILLI,
                averageSpanCount = samples.map(PhysicsEpochMetrics::spanCount).average(),
                averageBucketReferences = samples.map(PhysicsEpochMetrics::bucketReferenceCount).average(),
                averageCandidateChecks = samples.map(PhysicsEpochMetrics::candidateChecks).average(),
                mailboxPeakSize = mailbox.peakSize,
                budgetMillis = config.budgetMillis,
                passed = p95Millis < config.budgetMillis
            )
        } finally {
            coordinator.close()
        }
    }

    private fun createScenario(
        world: CoreWorld,
        config: StressConfig,
        scenario: StressScenario,
        trainCount: Int
    ): List<StressController> {
        val carLengths = List(config.carCount) { CAR_LENGTH_METERS }
        val totalEpochs = config.warmupEpochs + config.measurementEpochs
        val movementDistance = config.speedMetersPerSecond * totalEpochs * LOGICAL_DELTA_MILLIS / 1_000.0
        val sharedRail = if (scenario == StressScenario.SHARED) {
            val requiredLength = sharedStartOffset(config.carCount) +
                trainCount * config.sharedSpacingMeters + movementDistance + TRACK_END_MARGIN_METERS
            createRail(world, requiredLength, config.trackShape)
        } else {
            null
        }

        return List(trainCount) { index ->
            val rail = sharedRail ?: createRail(
                world,
                movementDistance + TRACK_END_MARGIN_METERS,
                config.trackShape,
                index * ISOLATED_TRACK_SPACING_METERS
            )
            val train = createTrainWithConfig("stress-$index", rail, world, carLengths)
            if (sharedRail != null) {
                placeSharedTrain(
                    train,
                    sharedRail,
                    sharedStartOffset(config.carCount) + index * config.sharedSpacingMeters
                )
            }
            val moving = scenario != StressScenario.MIXED || index % MIXED_STATIONARY_MODULUS != 0
            StressController(
                controlledTrain = train,
                requestedSpeedMetersPerSecond = if (moving) config.speedMetersPerSecond else 0.0
            ).also(train::setController)
        }.also { check(it.size == trainCount) }
    }

    private fun createRail(
        world: CoreWorld,
        lengthMeters: Double,
        trackShape: TrackShape,
        lateralOffsetMeters: Double = 0.0
    ): CoreRail =
        createStraightRailFromSegments(
            world,
            when (trackShape) {
                TrackShape.STRAIGHT -> PrimeCurveGenerator.buildStraight(
                    length = lengthMeters.coerceAtLeast(MINIMUM_TRACK_LENGTH_METERS),
                    segmentCount = 1,
                    step = TRACK_SAMPLE_STEP_METERS
                )
                TrackShape.CURVED -> buildCurvedTrack(lengthMeters.coerceAtLeast(MINIMUM_TRACK_LENGTH_METERS))
            }.map { curve ->
                if (lateralOffsetMeters == 0.0) curve else PrimeCurve.fromPoints(
                    curve.geometryBreakpoints().map { point ->
                        Curve.RawPoint(
                            position = Vector3d(point.position).add(0.0, 0.0, lateralOffsetMeters),
                            tangent = point.tangent,
                            normal = point.normal,
                            arcLength = point.arcLength
                        )
                    }
                )
            }
        )

    private fun buildCurvedTrack(minimumLengthMeters: Double): List<PrimeCurve> {
        val sampleCount = ceil(minimumLengthMeters / TRACK_SAMPLE_STEP_METERS).toInt().coerceAtLeast(1)
        val parameterStep = minimumLengthMeters / sampleCount
        val angularFrequency = 2.0 * PI / CURVED_TRACK_WAVELENGTH_METERS
        var cumulativeArc = 0.0
        var previousPosition: Vector3d? = null
        var maximumOffset = 0.0
        val points = (0..sampleCount).map { index ->
            val x = index * parameterStep
            val z = CURVED_TRACK_AMPLITUDE_METERS * sin(angularFrequency * x)
            val position = Vector3d(x, 0.0, z)
            previousPosition?.let { cumulativeArc += position.distance(it) }
            previousPosition = position
            maximumOffset = maxOf(maximumOffset, abs(z))
            Curve.RawPoint(
                position = position,
                tangent = Vector3d(
                    1.0,
                    0.0,
                    CURVED_TRACK_AMPLITUDE_METERS * angularFrequency * cos(angularFrequency * x)
                ).normalize(),
                normal = Vector3d(0.0, 1.0, 0.0),
                arcLength = cumulativeArc
            )
        }
        check(maximumOffset >= CURVED_TRACK_AMPLITUDE_METERS * 0.95) {
            "curved stress track did not reach the configured lateral amplitude"
        }
        check(points.zipWithNext().any { (first, second) -> first.tangent.dot(second.tangent) < 0.999999 }) {
            "curved stress track did not vary its tangent"
        }
        return listOf(PrimeCurve.fromPoints(points))
    }

    private fun placeSharedTrain(train: CoreTrain, rail: CoreRail, headOffsetMeters: Double) {
        val headLocation = RailLocationWithFacing(rail.viewAB, 0.0).forward(headOffsetMeters)
            ?: error("shared stress rail is too short for train ${train.id} at $headOffsetMeters m")
        train.world.onOwnerForTest {
            train.setLocation(headLocation)
            train.commitMaintenancePlacement()
        }
    }

    private fun enforceRequestedCapacity(config: StressConfig, results: List<StressRunResult>) {
        if (config.requiredTrainCount <= 0) return
        require(config.requiredTrainCount in config.trainCounts) {
            "physicsStress.requiredTrainCount=${config.requiredTrainCount} is not present in physicsStress.trainCounts"
        }
        config.workerThreads.forEach { workers ->
            config.scenarios.forEach { scenario ->
                val matching = results.filter {
                    it.workerThreads == workers &&
                        it.scenario == scenario.id &&
                        it.trainCount == config.requiredTrainCount
                }
                assertEquals(config.repetitions, matching.size)
                assertTrue(
                    matching.all(StressRunResult::passed),
                    "${scenario.id} with $workers workers did not sustain ${config.requiredTrainCount} trains " +
                        "under the ${config.budgetMillis} ms p95 budget"
                )
            }
        }
    }

    private fun writeReports(report: StressReport, reportDirectory: Path) {
        Files.createDirectories(reportDirectory)
        Files.writeString(reportDirectory.resolve("physics-capacity.json"), JSON.encodeToString(report))
        Files.writeString(reportDirectory.resolve("physics-capacity.csv"), report.toCsv())
        Files.writeString(reportDirectory.resolve("physics-capacity.md"), report.toMarkdown())
    }

    private class StressController(
        val controlledTrain: CoreTrain,
        private val requestedSpeedMetersPerSecond: Double
    ) : TrainController(controlledTrain) {
        var tickCount: Long = 0
            private set

        override fun tick(deltaT: Double) {
            tickCount++
            speed = requestedSpeedMetersPerSecond
            moveControlledTrain(activeSpeed * deltaT * 0.001, activeSpeed)
        }
    }

    private enum class StressScenario(val id: String) {
        ISOLATED("isolated"),
        MIXED("mixed"),
        SHARED("shared");

        companion object {
            fun parse(value: String): StressScenario = entries.firstOrNull { it.id == value.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "unknown physics stress scenario '$value'; expected ${entries.joinToString { it.id }}"
                )
        }
    }

    private enum class TrackShape(val id: String) {
        STRAIGHT("straight"),
        CURVED("curved");

        companion object {
            fun parse(value: String): TrackShape = entries.firstOrNull { it.id == value.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "unknown physics stress track shape '$value'; expected ${entries.joinToString { it.id }}"
                )
        }
    }

    private data class StressConfig(
        val trainCounts: List<Int>,
        val workerThreads: List<Int>,
        val carCount: Int,
        val warmupEpochs: Int,
        val measurementEpochs: Int,
        val repetitions: Int,
        val budgetMillis: Double,
        val scenarios: List<StressScenario>,
        val trackShape: TrackShape,
        val speedMetersPerSecond: Double,
        val sharedSpacingMeters: Double,
        val requiredTrainCount: Int,
        val reportDirectory: Path
    ) {
        fun toSnapshot() = StressConfigSnapshot(
            trainCounts,
            workerThreads,
            carCount,
            warmupEpochs,
            measurementEpochs,
            repetitions,
            budgetMillis,
            scenarios.map(StressScenario::id),
            trackShape.id,
            CURVED_TRACK_AMPLITUDE_METERS,
            CURVED_TRACK_WAVELENGTH_METERS,
            speedMetersPerSecond,
            sharedSpacingMeters,
            requiredTrainCount
        )

        companion object {
            fun fromSystemProperties(): StressConfig {
                val availableWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
                val carCount = positiveInt("physicsStress.carCount", 5)
                val sharedSpacing = positiveDouble(
                    "physicsStress.sharedSpacingMeters",
                    carCount * CAR_LENGTH_METERS + SHARED_CLEARANCE_METERS
                )
                require(sharedSpacing > carCount * CAR_LENGTH_METERS + 1.0) {
                    "physicsStress.sharedSpacingMeters must exceed train length plus one metre"
                }
                return StressConfig(
                    trainCounts = positiveIntList("physicsStress.trainCounts", "50,100,200,500"),
                    workerThreads = positiveIntList("physicsStress.workerThreads", availableWorkers.toString()),
                    carCount = carCount,
                    warmupEpochs = positiveInt("physicsStress.warmupEpochs", 600),
                    measurementEpochs = positiveInt("physicsStress.measurementEpochs", 2_400),
                    repetitions = positiveInt("physicsStress.repetitions", 3),
                    budgetMillis = positiveDouble("physicsStress.budgetMillis", 50.0),
                    scenarios = stringList("physicsStress.scenarios", "isolated,mixed,shared")
                        .map(StressScenario::parse).distinct(),
                    trackShape = TrackShape.parse(System.getProperty("physicsStress.trackShape", "straight")),
                    speedMetersPerSecond = positiveDouble("physicsStress.speedMetersPerSecond", 10.0),
                    sharedSpacingMeters = sharedSpacing,
                    requiredTrainCount = nonNegativeInt("physicsStress.requiredTrainCount", 0),
                    reportDirectory = Path.of(requiredProperty("physicsStress.reportDir"))
                )
            }

            private fun requiredProperty(name: String): String =
                System.getProperty(name)?.takeIf(String::isNotBlank)
                    ?: error("missing required system property $name")

            private fun positiveInt(name: String, default: Int): Int =
                System.getProperty(name, default.toString()).toInt().also {
                    require(it > 0) { "$name must be > 0" }
                }

            private fun nonNegativeInt(name: String, default: Int): Int =
                System.getProperty(name, default.toString()).toInt().also {
                    require(it >= 0) { "$name must be >= 0" }
                }

            private fun positiveDouble(name: String, default: Double): Double =
                System.getProperty(name, default.toString()).toDouble().also {
                    require(it.isFinite() && it > 0.0) { "$name must be finite and > 0" }
                }

            private fun positiveIntList(name: String, default: String): List<Int> =
                stringList(name, default).map { token ->
                    token.toInt().also { require(it > 0) { "$name entries must be > 0" } }
                }.distinct()

            private fun stringList(name: String, default: String): List<String> =
                System.getProperty(name, default).split(',').map(String::trim).filter(String::isNotEmpty)
                    .also { require(it.isNotEmpty()) { "$name must not be empty" } }
        }
    }

    @Serializable
    private data class StressConfigSnapshot(
        val trainCounts: List<Int>,
        val workerThreads: List<Int>,
        val carsPerTrain: Int,
        val warmupEpochs: Int,
        val measurementEpochs: Int,
        val repetitions: Int,
        val budgetMillis: Double,
        val scenarios: List<String>,
        val trackShape: String,
        val curvedTrackAmplitudeMeters: Double,
        val curvedTrackWavelengthMeters: Double,
        val speedMetersPerSecond: Double,
        val sharedSpacingMeters: Double,
        val requiredTrainCount: Int
    )

    @Serializable
    private data class StressRunResult(
        val scenario: String,
        val trainCount: Int,
        val carsPerTrain: Int,
        val workerThreads: Int,
        val repetition: Int,
        val warmupEpochs: Int,
        val measurementEpochs: Int,
        val wallP50Millis: Double,
        val wallP95Millis: Double,
        val wallP99Millis: Double,
        val wallMaxMillis: Double,
        val workerMaxP95Millis: Double,
        val workerTotalP95Millis: Double,
        val coordinatorP95Millis: Double,
        val averageSpanCount: Double,
        val averageBucketReferences: Double,
        val averageCandidateChecks: Double,
        val mailboxPeakSize: Int,
        val budgetMillis: Double,
        val passed: Boolean
    )

    @Serializable
    private data class StressEnvironment(
        val gitRevision: String,
        val javaVersion: String,
        val javaVm: String,
        val os: String,
        val architecture: String,
        val availableProcessors: Int,
        val cpuModel: String,
        val maxHeapBytes: Long
    ) {
        companion object {
            fun capture() = StressEnvironment(
                gitRevision = resolveGitRevision(),
                javaVersion = System.getProperty("java.version"),
                javaVm = System.getProperty("java.vm.name"),
                os = System.getProperty("os.name") + " " + System.getProperty("os.version"),
                architecture = System.getProperty("os.arch"),
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                cpuModel = resolveCpuModel(),
                maxHeapBytes = Runtime.getRuntime().maxMemory()
            )
        }
    }

    @Serializable
    private data class StressReport(
        val environment: StressEnvironment,
        val config: StressConfigSnapshot,
        val results: List<StressRunResult>
    ) {
        fun toCsv(): String = buildString {
            appendLine(
                "git_revision,track_shape,scenario,train_count,cars_per_train,worker_threads,repetition," +
                    "warmup_epochs,measurement_epochs,wall_p50_ms,wall_p95_ms,wall_p99_ms,wall_max_ms," +
                    "worker_max_p95_ms,worker_total_p95_ms,coordinator_p95_ms,average_spans,average_bucket_refs," +
                    "average_candidate_checks,mailbox_peak,budget_ms,passed"
            )
            results.forEach { result ->
                appendLine(
                    listOf(
                        environment.gitRevision,
                        config.trackShape,
                        result.scenario,
                        result.trainCount,
                        result.carsPerTrain,
                        result.workerThreads,
                        result.repetition,
                        result.warmupEpochs,
                        result.measurementEpochs,
                        result.wallP50Millis,
                        result.wallP95Millis,
                        result.wallP99Millis,
                        result.wallMaxMillis,
                        result.workerMaxP95Millis,
                        result.workerTotalP95Millis,
                        result.coordinatorP95Millis,
                        result.averageSpanCount,
                        result.averageBucketReferences,
                        result.averageCandidateChecks,
                        result.mailboxPeakSize,
                        result.budgetMillis,
                        result.passed
                    ).joinToString(",")
                )
            }
        }

        fun toMarkdown(): String = buildString {
            appendLine("# Core headless physics capacity")
            appendLine()
            appendLine("> This report excludes Paper/Folia scheduling, display entities, packets, and clients.")
            appendLine()
            appendLine("- Git revision: `${environment.gitRevision}`")
            appendLine("- JVM: ${environment.javaVersion} (${environment.javaVm})")
            appendLine("- OS: ${environment.os} / ${environment.architecture}")
            appendLine("- CPU: ${environment.cpuModel}; ${environment.availableProcessors} available processors")
            appendLine("- Max heap: ${environment.maxHeapBytes} bytes")
            appendLine("- Budget: p95 < ${config.budgetMillis} ms")
            appendLine("- Matrix: ${config.trainCounts.joinToString()} trains; ${config.workerThreads.joinToString()} workers")
            appendLine("- Train: ${config.carsPerTrain} cars; ${config.speedMetersPerSecond} m/s")
            appendLine("- Track shape: ${config.trackShape}")
            if (config.trackShape == TrackShape.CURVED.id) {
                appendLine(
                    "- Curved track: ${config.curvedTrackAmplitudeMeters} m lateral amplitude; " +
                        "${config.curvedTrackWavelengthMeters} m wavelength"
                )
            }
            appendLine(
                "- Sampling: ${config.warmupEpochs} warmup epochs; " +
                    "${config.measurementEpochs} measured epochs; ${config.repetitions} repetitions"
            )
            appendLine("- Shared-track spacing: ${config.sharedSpacingMeters} m")
            appendLine()
            appendLine("| Scenario | Workers | Trains | Repetition | p50 ms | p95 ms | p99 ms | Max ms | Worker max p95 ms | Worker total p95 ms | Coordinator p95 ms | Candidates/epoch | Pass |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|")
            results.forEach { result ->
                appendLine(
                    "| ${result.scenario} | ${result.workerThreads} | ${result.trainCount} | ${result.repetition} | " +
                        "${format(result.wallP50Millis)} | ${format(result.wallP95Millis)} | " +
                        "${format(result.wallP99Millis)} | ${format(result.wallMaxMillis)} | " +
                        "${format(result.workerMaxP95Millis)} | ${format(result.workerTotalP95Millis)} | " +
                        "${format(result.coordinatorP95Millis)} | " +
                        "${format(result.averageCandidateChecks)} | ${if (result.passed) "yes" else "no"} |"
                )
            }
            appendLine()
            appendLine("## Maximum passing train count")
            appendLine()
            results.groupBy { it.scenario to it.workerThreads }.forEach { (key, group) ->
                val passing = group.groupBy(StressRunResult::trainCount)
                    .filterValues { repetitions -> repetitions.size == config.repetitions && repetitions.all(StressRunResult::passed) }
                    .keys.maxOrNull()
                appendLine("- ${key.first}, ${key.second} workers: ${passing ?: "none"}")
            }
        }
    }

    private object DummyTickManager : TickManager {
        override fun register(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun unregister(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit


        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> =
            FutureTask(task).also(FutureTask<T>::run)
    }

    companion object {
        private const val LOGICAL_DELTA_MILLIS = 50.0
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val CAR_LENGTH_METERS = 10.0
        private const val TRACK_SAMPLE_STEP_METERS = 5.0
        private const val MINIMUM_TRACK_LENGTH_METERS = 500.0
        private const val TRACK_END_MARGIN_METERS = 2_000.0
        private const val SHARED_CLEARANCE_METERS = 5.0
        private const val CURVED_TRACK_AMPLITUDE_METERS = 20.0
        private const val CURVED_TRACK_WAVELENGTH_METERS = 500.0
        // Isolated worlds contain separate physical lines, rather than overlapping duplicate rails.
        private const val ISOLATED_TRACK_SPACING_METERS = 100.0
        private const val MIXED_STATIONARY_MODULUS = 4
        private const val SHARED_START_MARGIN_METERS = 10.0
        private val JSON = Json { prettyPrint = true }

        private fun sharedStartOffset(carCount: Int): Double =
            carCount * CAR_LENGTH_METERS + SHARED_START_MARGIN_METERS

        private fun percentileNanos(values: List<Long>, percentile: Double): Long {
            require(values.isNotEmpty())
            val sorted = values.sorted()
            val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
            return sorted[index]
        }

        private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

        private fun resolveCpuModel(): String {
            val cpuInfo = Path.of("/proc/cpuinfo")
            if (cpuInfo.exists()) {
                cpuInfo.readLines().firstOrNull { it.startsWith("model name") }
                    ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)?.let { return it }
            }
            return System.getenv("PROCESSOR_IDENTIFIER")?.takeIf(String::isNotBlank) ?: "unknown"
        }

        private fun resolveGitRevision(): String {
            var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            while (current != null) {
                val dotGit = current.resolve(".git")
                if (dotGit.exists()) {
                    val gitDirectory = if (dotGit.isDirectory()) {
                        dotGit
                    } else {
                        val raw = dotGit.readText().trim()
                        current.resolve(raw.removePrefix("gitdir:").trim()).normalize()
                    }
                    val head = gitDirectory.resolve("HEAD").readText().trim()
                    if (!head.startsWith("ref:")) return head
                    val ref = head.removePrefix("ref:").trim()
                    val looseRef = gitDirectory.resolve(ref)
                    if (looseRef.exists()) return looseRef.readText().trim()
                    val packedRefs = gitDirectory.resolve("packed-refs")
                    if (packedRefs.exists()) {
                        packedRefs.readLines().firstOrNull { it.endsWith(" $ref") }
                            ?.substringBefore(' ')?.let { return it }
                    }
                    return "unknown:$ref"
                }
                current = current.parent
            }
            return "unknown"
        }
    }
}
