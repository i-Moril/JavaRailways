package work.art1st.javarailways.bukkit.logic

import org.bukkit.World as BukkitWorld
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.diagnostics.PluginDiagnosticLog
import work.art1st.javarailways.simpleatobukkit.diagnostics.AtoStallDiagnostics
import work.art1st.javarailways.bukkit.extension.BukkitWorldData
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContext
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.basic.safety.RailDerivedSpeedMetadata
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.operation.BukkitTrainOperationGateway
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.PlatformCleanup
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.Executors
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.rail.OperatingDirectionStore
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime

/**
 * 为 Bukkit 世界创建对应的 Core [World]：Tick、物理协调器、[BukkitWorldData] 扩展一步完成。
 */
object RailWorldFactory {
    @OptIn(Heavy::class)
    fun createForBukkit(
        bukkitWorld: BukkitWorld,
        infrastructure: CoreWorld.SavedInfrastructureV2? = null,
        runtime: CoreWorld.SavedRuntimeV1? = null,
        basicInfrastructure: BasicInfrastructureSnapshotV2? = null,
        simpleAtoInfrastructure: SimpleATOInfrastructureSnapshotV1? = null,
        basicRuntime: BasicRuntimeSnapshotV3? = null
    ): World {
        ThreadGuard.ensureMainThread("RailWorldFactory.createForBukkit")
        val construction = WorldConstructionResources()
        return try {
            createOwned(
                bukkitWorld,
                infrastructure,
                runtime,
                basicInfrastructure,
                simpleAtoInfrastructure,
                basicRuntime,
                construction
            )
        } catch (failure: Throwable) {
            construction.fail(failure)
        }
    }

    @OptIn(Heavy::class)
    private fun createOwned(
        bukkitWorld: BukkitWorld,
        infrastructure: CoreWorld.SavedInfrastructureV2?,
        runtime: CoreWorld.SavedRuntimeV1?,
        basicInfrastructure: BasicInfrastructureSnapshotV2?,
        simpleAtoInfrastructure: SimpleATOInfrastructureSnapshotV1?,
        basicRuntime: BasicRuntimeSnapshotV3?,
        construction: WorldConstructionResources
    ): World {
        val tickManager = TickManagerImpl(
            Executors.newSingleThreadScheduledExecutor(),
            System.currentTimeMillis()
        ).also { construction.own(it::close) }
        val physicsCoordinator = PhysicsCoordinator(
            workerThreads = BukkitPlugin.INSTANCE.physicsConfig.workerThreads
        ).also { construction.own(it::close) }
        val dependency = CoreWorld.SnapshotDependency(tickManager, physicsCoordinator)
        val coreWorld = if (infrastructure != null) {
            CoreWorld.load(infrastructure, runtime, dependency)
        } else {
            CoreWorld.createEmpty(tickManager, physicsCoordinator)
        }
        val renderChunkManager = RenderChunkManager(bukkitWorld)
            .also { construction.own(it::close) }
        coreWorld.registerExtensionData(
            BukkitPlugin.INSTANCE,
            BukkitWorldData(renderChunkManager, bukkitWorld)
        )
        val plugin = BukkitPlugin.INSTANCE
        val atoLogger = plugin.logger
        val pluginDataFolder = plugin.dataFolder
        val physicsConfig = plugin.physicsConfig
        val atoSafetyDistanceMeters = plugin.javaRailwaysConfig.atoSafetyDistanceMeters
        var boundRouteAdmission: RouteResourceAdmissionService? = null
        val routeAdmissionProvider = TimetableRouteAdmissionContextProvider {
            val observation = plugin.railwayObservationService.latest(bukkitWorld.uid).orElse(null)
                ?: return@TimetableRouteAdmissionContextProvider null
            val admission = boundRouteAdmission
                ?: plugin.infrastructureResourceService.routeAdmission(plugin, bukkitWorld.uid)
                    .also { boundRouteAdmission = it }
            TimetableRouteAdmissionContext(observation, admission)
        }
        val infrastructureDependencyService = object : InfrastructureDependencyService {
            override fun set(request: InfrastructureDependencyRequest) =
                plugin.infrastructureResourceService.setInfrastructureDependencies(
                    plugin,
                    bukkitWorld.uid,
                    request
                )

            override fun remove(owner: InfrastructureOwner) =
                plugin.infrastructureResourceService.removeInfrastructureDependencies(
                    plugin,
                    bukkitWorld.uid,
                    owner
                )
        }
        val couplingControlReleased: (work.art1st.javarailways.core.api.physics.train.Train) -> Unit = { train ->
            plugin.trainControlManager.retireCouplingControl(train)
            work.art1st.javarailways.bukkit.command.TrainCommand.retireCouplingControl(train)
        }
        OperationalWorldRuntime.create(
            coreWorld, bukkitWorld.uid.toString(),
            couplingModeSettings = work.art1st.javarailways.simpleato.api.coupling.CouplingModeSettings(
                plugin.javaRailwaysConfig.couplingMaximumEntryGapMeters,
                plugin.javaRailwaysConfig.couplingMaximumApproachSpeedMetersPerSecond
            ),
            couplingAdmissionContextProvider = routeAdmissionProvider,
            couplingControlReleased = couplingControlReleased,
            candidateFacts = { train ->
                work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts.fromCars(
                    train.cars.associate { car -> car.id to (car.carRendererOrNull?.resolveCandidateFacts()
                        ?: work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts(null, emptySet(), null, emptySet())) }
                )
            },
            basicDataFactory = { directionChanges ->
                BasicWorldData(
                    operatingDirections = OperatingDirectionStore(directionChanges)
                )
            },
            reportStall = { report ->
                atoLogger.severe(report.format())
                PluginDiagnosticLog.ato("WORLD_STALLED", report.format())
                runCatching { AtoStallDiagnostics.write(pluginDataFolder, report) }
                    .onSuccess { file ->
                        atoLogger.warning("ATO Stall diagnostics written to ${file.absolutePath}")
                    }
                    .onFailure { error ->
                        atoLogger.severe(
                            "Failed to write ATO Stall diagnostics: " +
                                "${error::class.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
            },
            dispatcherFactory = { operational ->
                val signalRestrictions = operational.signalRestrictions
                val inspectionRuntime = operational.inspectionRuntime
                TimetableDispatcher(
                    authorityStoppingMarginMeters = atoSafetyDistanceMeters,
                    admissionRetryTicks = plugin.javaRailwaysConfig.atoAdmissionRetryTicks,
                    maximumCouplingGapMeters = plugin.javaRailwaysConfig.couplingMaximumEntryGapMeters,
                    maximumCouplingApproachSpeedMetersPerSecond = plugin.javaRailwaysConfig.couplingMaximumApproachSpeedMetersPerSecond,
                    couplingControlReleased = couplingControlReleased,
                    routeAdmissionContextProvider = routeAdmissionProvider,
                    signalRestrictionSnapshots = signalRestrictions,
                    inspectionRuntime = inspectionRuntime,
                    infrastructureDependencyService = infrastructureDependencyService,
                    operationGateway = BukkitTrainOperationGateway(coreWorld),
                    atoFactory = TimetableAtoFactory { train, world ->
                        require(!(train as CoreTrain).movementHeld) { "Train ${train.id} configuration is unavailable; see /jr issues" }
                        val renderer = train.rendererOrNull
                            ?: throw IllegalArgumentException("Train ${train.id} has no model control profile")
                        val trainProfile = renderer.resolveControl()
                        val carProfiles = train.cars.mapIndexed { index, car ->
                            val modelName = car.carRendererOrNull?.carModelName
                                ?: throw IllegalArgumentException(
                                    "Train ${train.id} car #${index + 1} has no model control profile"
                                )
                            car.carRendererOrNull?.resolveCarControl()
                                ?: throw IllegalArgumentException(
                                    "Train ${train.id} car #${index + 1} model '$modelName' has no control profile"
                                )
                        }
                        val gravity = physicsConfig.track.gravityMetersPerSecondSquared
                        val profile = AtoSafetyProfile(
                            safetyDistanceMeters = atoSafetyDistanceMeters,
                            serviceDecelerationMetersPerSecondSquared =
                                TrainDynamics.flatServiceDeceleration(trainProfile, carProfiles, gravity),
                            reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
                                trainProfile,
                                carProfiles,
                                gravity
                            )
                        )
                        SimpleATO(
                            train,
                            world,
                            HandleTrainActuator(train, trainProfile, carProfiles, gravity,
                                profileResolver = { car -> car.carRendererOrNull?.resolveCarControl() },
                                initialDeviceSource = train.controller as? work.art1st.javarailways.basic.api.ato.DynamicsProfileSource),
                            profile,
                            inspectionRuntime::maximumDeclaredLeadTimeSeconds
                        )
                    }
                )
            }
        ).also { construction.own(it::close) }
        basicInfrastructure?.restore(coreWorld)
        simpleAtoInfrastructure?.restore(coreWorld)
        basicRuntime?.restore()
        RailDerivedSpeedMetadata.refreshWorld(
            coreWorld, plugin.physicsConfig.railSpeedPhysics(),
            plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared
        )
        // Visual restoration can fail before this factory returns a world to WorldManager.
        // Keep the global visual index under construction ownership until the handoff.
        construction.own {
            RailVisualBootstrap.retireWorld(coreWorld).whenComplete { _, cleanupFailure ->
                if (cleanupFailure != null) plugin.logger.log(java.util.logging.Level.SEVERE,
                    "Rail visual cleanup failed for uninstalled world ${bukkitWorld.uid}", cleanupFailure)
            }
        }
        val persisted = RailRenderMetaStore.load(bukkitWorld)
        val restored = persisted.filter { RailVisualBootstrap.fromPersisted(coreWorld, it) }
        if (restored.size != persisted.size) {
            RailRenderMetaStore.saveAll(bukkitWorld, restored)
        }
        construction.release()
        return coreWorld
    }
}

internal class WorldConstructionResources {
    private val cleanup = ArrayDeque<() -> Unit>()

    fun own(close: () -> Unit) {
        cleanup.addFirst(close)
    }

    fun release() {
        cleanup.clear()
    }

    fun fail(primary: Throwable): Nothing {
        try {
            BukkitThreadGuardBridge.runOutsideSimulationContext {
                PlatformCleanup.runAndAwait {
                    cleanup.forEach { close ->
                        try {
                            close()
                        } catch (cleanupFailure: Throwable) {
                            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
                        }
                    }
                }
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
        } finally {
            cleanup.clear()
        }
        throw primary
    }
}
