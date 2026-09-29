package work.art1st.javarailways.bukkit.logic

import org.bukkit.World as BukkitWorld
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.diagnostics.PluginDiagnosticLog
import work.art1st.javarailways.simpleatobukkit.diagnostics.AtoStallDiagnostics
import work.art1st.javarailways.bukkit.extension.BukkitWorldData
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.di.KoinWorldParentContext
import work.art1st.javarailways.core.di.javaRailwaysParentScope
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.safety.WorldSafetyInterlock
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
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.basic.route.TurnoutSafetyDefaults
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.operation.BukkitTrainOperationGateway
import work.art1st.javarailways.bukkit.operation.RegionTrainOperationDispatcher
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.Executors

/**
 * 为 Bukkit 世界创建对应的 Core [World]：Tick、Koin 父 scope、[BukkitWorldData] 扩展一步完成。
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
        val tickManager = TickManagerImpl(
            Executors.newSingleThreadScheduledExecutor(),
            System.currentTimeMillis()
        )
        val physicsCoordinator = PhysicsCoordinator(
            workerThreads = BukkitPlugin.INSTANCE.physicsConfig.workerThreads
        )
        val worldParent = KoinWorldParentContext(javaRailwaysParentScope())
        val dependency = CoreWorld.SnapshotDependency(tickManager, worldParent, physicsCoordinator)
        val coreWorld = if (infrastructure != null) {
            CoreWorld.load(infrastructure, runtime, dependency)
        } else {
            CoreWorld.createEmpty(tickManager, worldParent, physicsCoordinator)
        }
        val renderChunkManager = RenderChunkManager(bukkitWorld)
        coreWorld.registerExtensionData(
            BukkitPlugin.INSTANCE,
            BukkitWorldData(renderChunkManager, bukkitWorld)
        )
        val plugin = BukkitPlugin.INSTANCE
        val atoLogger = plugin.logger
        val pluginDataFolder = plugin.dataFolder
        val physicsConfig = plugin.physicsConfig
        val safetyInterlock = WorldSafetyInterlock()
        val basicData = BasicWorldData(
            safetyInterlock = safetyInterlock,
            turnoutSafetyDefaults = physicsConfig.turnoutSafety.let {
                TurnoutSafetyDefaults(
                    protectionDistanceMeters = it.defaultProtectionDistanceMeters,
                    lockingDistanceMeters = it.defaultLockingDistanceMeters,
                    decisionLeadTicks = it.decisionLeadTicks,
                    retryTicks = it.allocationRetryTicks
                )
            }
        )
        val atoCoordinator = AtoWorldCoordinator(
            bukkitWorld.uid.toString(),
            safetyInterlock
        ) { report ->
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
        }
        coreWorld.registerExtensionData(BasicPlugin.INSTANCE, basicData)
        var boundRouteAdmission: RouteResourceAdmissionService? = null
        val routeAdmissionProvider = TimetableRouteAdmissionContextProvider {
            val observation = plugin.railwayObservationService.latest(bukkitWorld.uid).orElse(null)
                ?: return@TimetableRouteAdmissionContextProvider null
            val admission = boundRouteAdmission
                ?: plugin.infrastructureResourceService.routeAdmission(plugin, bukkitWorld.uid)
                    .also { boundRouteAdmission = it }
            TimetableRouteAdmissionContext(observation, admission)
        }
        val signalRestrictions = TrainRestrictionSnapshotStore(bukkitWorld.uid.toString())
        // Dispatcher owns rich train/route failure reporting for synchronous update failures.
        // Bukkit service-time source changes use DefaultSignalEpochSourceService.stallWorld.
        val inspectionRuntime = InspectionRuntime(signalRestrictions) { _, _, _ -> }
        val infrastructureDependencyService = object : InfrastructureDependencyService {
            override fun set(request: work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest) =
                plugin.infrastructureResourceService.setInfrastructureDependencies(
                    plugin,
                    bukkitWorld.uid,
                    request
                )

            override fun remove(owner: work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner) =
                plugin.infrastructureResourceService.removeInfrastructureDependencies(
                    plugin,
                    bukkitWorld.uid,
                    owner
                )
        }
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = routeAdmissionProvider,
            signalRestrictionSnapshots = signalRestrictions,
            inspectionRuntime = inspectionRuntime,
            infrastructureDependencyService = infrastructureDependencyService,
            operationGateway = BukkitTrainOperationGateway(
                coreWorld,
                operationDispatcher = RegionTrainOperationDispatcher(BukkitPlugin.INSTANCE)
            ),
            atoFactory = TimetableAtoFactory { train, world ->
                val renderer = train.rendererOrNull
                    ?: throw IllegalArgumentException("Train ${train.id} has no model control profile")
                val trainProfile = renderer.resolveControl()
                val carProfiles = train.cars.mapIndexed { index, car ->
                    val modelName = car.carRendererOrNull?.carModelName
                        ?: throw IllegalArgumentException(
                            "Train ${train.id} car #${index + 1} has no model control profile"
                        )
                    renderer.resolveCarControl(modelName)
                        ?: throw IllegalArgumentException(
                            "Train ${train.id} car #${index + 1} model '$modelName' has no control profile"
                        )
                }
                val gravity = physicsConfig.track.gravityMetersPerSecondSquared
                val profile = AtoSafetyProfile(
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
                    HandleTrainActuator(train, trainProfile, carProfiles, gravity),
                    profile,
                    inspectionRuntime::maximumDeclaredLeadTimeSeconds
                )
            }
        )
        val signalEpochRuntime = SignalEpochRuntime(
            worldId = bukkitWorld.uid.toString(),
            publisher = signalRestrictions,
            completeEpoch = signalRestrictions::completeEpoch,
            invalidateInspectionDeclarations = inspectionRuntime::markDirty
        )
        physicsCoordinator.registerRailwayEpochProcessor(signalEpochRuntime)
        coreWorld.registerExtensionData(
            SimpleATOPlugin.INSTANCE,
            SimpleATOWorldData(
                atoCoordinator,
                dispatcher,
                signalEpochRuntime,
                signalRestrictions,
                inspectionRuntime,
                basicData.railObjects,
                basicData.safetyInterlock
            )
        )
        basicInfrastructure?.restore(coreWorld)
        simpleAtoInfrastructure?.restore(coreWorld)
        basicRuntime?.restore()
        OperationalSpeedLimitSignalMaterializer.materialize(coreWorld)
        val persisted = RailRenderMetaStore.load(bukkitWorld)
        val restored = persisted.filter { RailVisualBootstrap.fromPersisted(coreWorld, it) }
        if (restored.size != persisted.size) {
            RailRenderMetaStore.saveAll(bukkitWorld, restored)
        }
        return coreWorld
    }
}
