package work.art1st.javarailways.bukkit.logic

import org.bukkit.World as BukkitWorld
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.diagnostics.PluginDiagnosticLog
import work.art1st.javarailways.bukkit.debug.AtoStallDiagnostics
import work.art1st.javarailways.bukkit.extension.BukkitWorldData
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.di.KoinWorldParentContext
import work.art1st.javarailways.core.di.javaRailwaysParentScope
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV1
import work.art1st.javarailways.basic.controller.SimpleATO
import work.art1st.javarailways.basic.timetable.TimetableAtoFactory
import work.art1st.javarailways.basic.timetable.TimetableDispatcher
import work.art1st.javarailways.basic.route.TurnoutSafetyDefaults
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
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
        basicInfrastructure: BasicInfrastructureSnapshotV1? = null,
        basicRuntime: BasicRuntimeSnapshotV1? = null
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
        val basicData = BasicWorldData(
                dispatcher = TimetableDispatcher(
                    operationGateway = BukkitTrainOperationGateway(
                    coreWorld,
                    operationDispatcher = RegionTrainOperationDispatcher(BukkitPlugin.INSTANCE)
                ),
                    atoFactory = TimetableAtoFactory { train, world ->
                        val profile = AtoSafetyProfile()
                        val maximumSpeed = train.rendererOrNull?.resolveControl()?.maxSpeed
                            ?: throw IllegalArgumentException("Train ${train.id} has no model control profile")
                        SimpleATO(
                            train,
                            world,
                            KinematicTrainActuator(maximumSpeed, profile),
                            profile
                        )
                    }
                ),
                atoCoordinator = AtoWorldCoordinator(bukkitWorld.uid.toString()) { report ->
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
                turnoutSafetyDefaults = physicsConfig.turnoutSafety.let {
                    TurnoutSafetyDefaults(
                        protectionDistanceMeters = it.defaultProtectionDistanceMeters,
                        lockingDistanceMeters = it.defaultLockingDistanceMeters,
                        decisionLeadTicks = it.decisionLeadTicks,
                        retryTicks = it.allocationRetryTicks
                    )
                }
            )
        coreWorld.registerExtensionData(BasicPlugin.INSTANCE, basicData)
        basicInfrastructure?.restore(coreWorld)
        basicRuntime?.restore(coreWorld)
        OperationalSpeedLimitSignalMaterializer.materialize(coreWorld)
        val persisted = RailRenderMetaStore.load(bukkitWorld)
        val restored = persisted.filter { RailVisualBootstrap.fromPersisted(coreWorld, it) }
        if (restored.size != persisted.size) {
            RailRenderMetaStore.saveAll(bukkitWorld, restored)
        }
        return coreWorld
    }
}
