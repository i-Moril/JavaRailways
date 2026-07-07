package work.art1st.mineRails.bukkit.control

import org.bukkit.entity.Player
import work.art1st.mineRails.core.api.physics.train.Train
import java.util.UUID

class TrainControlManager {
    data class ManualControlSession(
        val trainId: String,
        val playerId: UUID,
        val controller: RealisticManualController
    )

    private val sessionsByPlayer: MutableMap<UUID, ManualControlSession> = mutableMapOf()
    private val sessionsByTrain: MutableMap<String, ManualControlSession> = mutableMapOf()

    fun ownerOf(train: Train): UUID? = sessionsByTrain[train.id]?.playerId

    fun sessionFor(player: Player): ManualControlSession? = sessionsByPlayer[player.uniqueId]

    fun sessionFor(train: Train): ManualControlSession? = sessionsByTrain[train.id]

    fun controllerFor(player: Player): RealisticManualController? = sessionFor(player)?.controller

    fun registerManualControl(player: Player, train: Train, controller: RealisticManualController): ManualControlSession {
        releaseManualControl(player.uniqueId)
        val previousForTrain = sessionsByTrain.remove(train.id)
        if (previousForTrain != null) {
            sessionsByPlayer.remove(previousForTrain.playerId)
        }
        val session = ManualControlSession(
            trainId = train.id,
            playerId = player.uniqueId,
            controller = controller
        )
        sessionsByPlayer[player.uniqueId] = session
        sessionsByTrain[train.id] = session
        return session
    }

    fun releaseManualControl(player: Player): ManualControlSession? = releaseManualControl(player.uniqueId)

    fun releaseManualControl(playerId: UUID): ManualControlSession? {
        val session = sessionsByPlayer.remove(playerId) ?: return null
        sessionsByTrain.remove(session.trainId)
        session.controller.coast()
        return session
    }

    fun releaseTrain(train: Train): ManualControlSession? {
        val session = sessionsByTrain.remove(train.id) ?: return null
        sessionsByPlayer.remove(session.playerId)
        session.controller.coast()
        return session
    }

    fun clear() {
        sessionsByPlayer.values.forEach { it.controller.coast() }
        sessionsByPlayer.clear()
        sessionsByTrain.clear()
    }
}
