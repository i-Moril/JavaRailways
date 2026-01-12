package work.art1st.mineRails.core.control.command

interface ControlCommand {
    /**
     * Add deltaT to the time
     * @param deltaT
     * @return
     */
    fun tick(deltaT: Double)

    /**
     * @return Speed at t+deltaT, >= 0
     */
    val speed: Double

    /**
     * @return DeltaS from t to t+deltaT, >= 0
     */
    val deltaS: Double

    /**
     * 
     * @return true if control command is finished
     */
    val isFinished: Boolean
}
