package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.util.plugin.Extension

interface TrainExtension : Extension<Train> {
    /**
     * Prepare the extension instance for the train beginning at [carId] in stable consist order.
     *
     * This method runs before Core publishes either half of the split. It must not mutate this
     * extension or perform externally visible work: a later extension or new-host initialization
     * can still fail, and Core has no generic protocol for rolling such effects back. The returned
     * dedicated instance is installed in the same extension slot and initialized against the new
     * train; returning this host's existing extension instance is invalid. That first initialize
     * call is still inside split preparation and may initialize only the returned extension's own
     * state—it must not mutate the shared cars or publish platform effects before it returns.
     */
    fun splitBefore(carId: String, newId: String): TrainExtension
    fun linkWith(otherTrain: Train)
}
