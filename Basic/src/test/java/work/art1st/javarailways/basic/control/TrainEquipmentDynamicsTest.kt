package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import work.art1st.javarailways.basic.api.ato.VehicleDeviceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import kotlin.math.min
import kotlin.math.max

/** Real nonzero device timings; these fixtures never substitute instant equipment. */
class TrainEquipmentDynamicsTest {
    private val profile=object:TrainDynamicsProfile {
        override val maxSpeed=40.0
        override val powerHandles=listOf("P1","P2","P3","P4")
        override val brakeHandles=(1..7).map { "B$it" }+"EB"
        override val brakeApplyTime=1.2
        override val brakeReleaseTime=1.8
        override val adhesionCoefficient=0.2
        override val tractionJerk=0.8
        override val brakeJerk=0.8
        override fun normalizedPowerScale(handle:String)=if(handle.startsWith("P"))handle.drop(1).toDouble()/4.0 else 0.0
        override fun normalizedBrakeScale(handle:String)=when {handle=="EB"->1.25;handle.startsWith("B")->handle.drop(1).toDouble()/7.0;else->0.0}
        override fun resistanceForceAt(speed:Double)=400.0+10.0*speed+1.2*speed*speed
    }
    private fun car(motor:Boolean=true)=object:CarDynamicsProfile {
        override val mass=32000.0
        override val effectiveMass=34560.0
        override val motorAdhesionMassRatio=if(motor)1.0 else 0.0
        override val brakeAdhesionMassRatio=1.0
        override val brakeMaxForce=45000.0
        override val electricBrakeMaxForce=if(motor)35000.0 else 0.0
        override fun motorForceAt(speed:Double)=if(motor)min(50000.0,500000.0/max(speed,0.5)) else 0.0
        override fun resistanceForceAt(speed:Double,defaultResistance:(Double)->Double)=defaultResistance(speed)
    }

    @Test fun `held air releases before traction and public prior snapshots stay stable`() {
        val car=car();val inputs=listOf(TrainDynamics.CarInput(car,-0.01))
        val initial=TrainDynamics.initialState(listOf(car))
        val retained=initial.devices.toList()
        var state=initial
        repeat(20) {
            val result=TrainDynamics.step(state,profile,inputs,"P4",9.80665,0.05)
            assertEquals(0.0,result.tractionForce)
            state=result.state
        }
        assertEquals(0.0,state.speed)
        repeat(120) { state=TrainDynamics.step(state,profile,inputs,"P4",9.80665,0.05).state }
        assertTrue(state.speed>0.0)
        assertEquals(retained,initial.devices.toList())
        val exported=initial.devices.toList().toMutableList();exported[0]=VehicleDeviceState()
        assertEquals(retained,initial.devices.toList())
    }

    @Test fun `reused candidate bank exactly matches owned production snapshots and isolates branches`() {
        val random=java.util.Random(781)
        for(n in listOf(4,8,16)) {
            val cars=List(n) { car(it%2==0) }
            val inputs=cars.mapIndexed { i,c -> TrainDynamics.CarInput(c,if(i%3==0)-0.01 else 0.005) }
            var stable=TrainDynamics.initialState(cars,3.01)
            val initial = stable
            val seed = initial.devices.toList()
            val workspace = TrainDynamics.PreviewWorkspace(n)
            repeat(320) { index ->
                val handle=when(index){in 0..60->"P4";in 61..80->"N";in 81..150->"B7";else->listOf("P1","P4","N","B2","B7","EB")[random.nextInt(6)]}
                val session = TrainDynamics.PreviewSession(stable, profile, inputs, 9.80665, 0.05, workspace)
                val expected = TrainDynamics.step(stable, profile, inputs, handle, 9.80665, 0.05)
                val actual = session.trial(handle)
                fun bits(a:Double,b:Double)=assertEquals(a.toRawBits(),b.toRawBits())
                bits(expected.state.speed,actual.state.motion.speed)
                bits(expected.state.acceleration,actual.state.motion.acceleration)
                bits(expected.distance,actual.distance)
                bits(expected.brakeForce,actual.brakeForce)
                bits(expected.tractionForce,actual.tractionForce)
                for(i in 0 until n) {
                    val device=expected.state.devices[i]
                    bits(device.pressureKpa,actual.state.devices.pressure[i])
                    bits(device.electricBrakeForceNewtons,actual.state.devices.electric[i])
                    bits(device.tractionForceNewtons,actual.state.devices.traction[i])
                    bits(device.commandAgeSeconds,actual.state.devices.age[i])
                    bits(device.previousDemand,actual.state.devices.previous[i])
                }
                val branchA = session.trial("P4").state.motion.speed
                session.trial("EB")
                session.trial("N")
                assertEquals(branchA.toRawBits(), session.trial("P4").state.motion.speed.toRawBits())
                assertEquals(stable.devices, session.seed.motion.devices)
                stable = expected.state
            }
            assertEquals(seed, initial.devices.toList())
        }
    }

    @Test fun `electric fade retains the guaranteed pneumatic floor after response`() {
        val cars=listOf(car(),car(false))
        val inputs=cars.map { TrainDynamics.CarInput(it) }
        val floor=TrainDynamics.steadyServiceBrakeForce(profile,cars,9.80665)
        assertEquals(55000.0,floor,1e-9)
        var state=TrainDynamics.initialState(cars,15.0)
        var faded=false
        repeat(1200) { tick ->
            val result=TrainDynamics.step(state,profile,inputs,"B7",9.80665,0.05)
            if(tick>80)assertTrue(result.brakeForce+1e-6>=floor)
            if(state.speed<3.0 && state.devices[0].electricBrakeForceNewtons>0.0)faded=true
            state=result.state
        }
        assertTrue(faded)
        assertEquals(0.0,state.speed)
        assertEquals(0.0,state.devices[0].electricBrakeForceNewtons)
    }

    @Test fun `positive acceleration envelope tightens speed but held rest can depart`() {
        val car=car();val cars=listOf(car)
        val segments=listOf(BrakingGradeSegment(1000.0,-0.01))
        val residual=GradeAwareBrakingEnvelope.fromDynamics(profile,cars,9.80665,segments,0.8)
        val coast=GradeAwareBrakingEnvelope.fromDynamics(profile,cars,9.80665,segments,0.0)
        val reaction=HandleTrainActuator.serviceBrakeReactionTimeSeconds(profile,cars,9.80665)
        assertTrue(residual.permittedSpeedMetersPerSecond(0.0,150.0,10.0,reaction)<
            coast.permittedSpeedMetersPerSecond(0.0,150.0,10.0,reaction))
        val rest=GradeAwareBrakingEnvelope.fromDynamics(profile,cars,9.80665,segments,0.0)
        assertTrue(rest.permittedSpeedMetersPerSecond(0.0,5.0,0.0,reaction)>0.0)
    }

    @Test
    fun `analytical response includes retained excess electric after configuration change`() {
        val configured = object : CarDynamicsProfile by car() {
            override val electricBrakeMaxForce = 0.0
        }
        fun response(electric: Double): work.art1st.javarailways.basic.api.ato.BrakingResponse {
            val state = TrainDynamics.State(5.0, -0.1, 1.0,
                TrainDynamics.DeviceSnapshot.of(listOf(VehicleDeviceState(
                    pressureKpa = 350.0, electricBrakeForceNewtons = electric,
                    commandAgeSeconds = 1.0, previousDemand = -1.0))))
            val preview = TrainDynamics.PreviewSession(state, profile,
                listOf(TrainDynamics.CarInput(configured)), 9.80665, 0.05)
            return ServiceBrakingResponse.bound(preview, "B7", listOf("B7"), 0.2)
        }
        val excess = response(35000.0)
        val cleared = response(0.0)
        assertTrue(excess.proven)
        assertTrue(excess.distanceMeters >= cleared.distanceMeters)
        assertTrue(excess.durationSeconds > cleared.durationSeconds)
    }

    @Test
    fun `analytical response reserves the full brake jerk ramp after air is held`() {
        val configured = car(false)
        val state = TrainDynamics.initialState(listOf(configured), 10.0, brakeScale = 1.0).copy(
            devices = TrainDynamics.DeviceSnapshot.of(listOf(VehicleDeviceState(
                pressureKpa = 350.0, commandAgeSeconds = 1.0, previousDemand = -1.0))))
        fun response(activeProfile: TrainDynamicsProfile) = ServiceBrakingResponse.bound(
            TrainDynamics.PreviewSession(state, activeProfile,
                listOf(TrainDynamics.CarInput(configured)), 9.80665, 0.05),
            "B7", listOf("B7"), 0.2)
        val withJerk = response(profile)
        val instantaneous = response(object : TrainDynamicsProfile by profile {
            override val brakeJerk = 0.0
        })
        val deceleration = configured.brakeMaxForce / configured.effectiveMass
        assertTrue(withJerk.proven)
        assertTrue(withJerk.durationSeconds >= deceleration / profile.brakeJerk)
        assertTrue(withJerk.distanceMeters > instantaneous.distanceMeters)
    }

    @Test
    fun `analytical response does not credit transient electric braking on a downhill grade`() {
        val configured = car()
        val state = TrainDynamics.State(5.0, 0.0, 0.0,
            TrainDynamics.DeviceSnapshot.of(listOf(VehicleDeviceState(
                electricBrakeForceNewtons = 35000.0, commandAgeSeconds = 1.0, previousDemand = -1.0))))
        val preview = TrainDynamics.PreviewSession(state, profile,
            listOf(TrainDynamics.CarInput(configured, -0.01)), 9.80665, 0.05)
        val response = ServiceBrakingResponse.bound(preview, "B7", listOf("B7"), 0.2)
        assertTrue(response.proven)
        assertTrue(response.speedGainMetersPerSecond > 0.0,
            "the downhill response must reserve acceleration before the pneumatic floor is ready")
    }

    @Test
    fun `analytical response bounds mixed future control intervals and retained device transients`() {
        val random = java.util.Random(719273L)
        val order = (7 downTo 1).map { "B$it" } + "N" + (1..4).map { "P$it" }
        repeat(100) { case ->
            val controls = List(4) { index ->
                object : CarDynamicsProfile by car(index % 2 == 0) {
                    override val equipment = TrainDynamics.Equipment(
                        commandDelaySeconds = if (case % 3 == 0) 0.0 else 0.1
                    )
                }
            }
            val activeProfile = object : TrainDynamicsProfile by profile {
                override val tractionJerk = listOf(0.0, 0.05, 0.8, 2.0)[case % 4]
                override fun normalizedBrakeScale(handle: String): Double =
                    profile.normalizedBrakeScale(handle) * if (case % 4 == 0) 2.0 else 1.0
            }
            val maximumDelta = listOf(0.075, 0.2, 0.35)[case % 3]
            val grade = listOf(-0.015, 0.0, 0.02)[case % 3]
            val inputs = controls.map { TrainDynamics.CarInput(it, grade) }
            val seed = TrainDynamics.State(
                speed = listOf(0.0, 0.01, 0.3, 3.0, 12.0)[case % 5],
                acceleration = random.nextDouble() * 1.8 - 0.6,
                devices = TrainDynamics.DeviceSnapshot.of(controls.map {
                    VehicleDeviceState(
                        pressureKpa = random.nextDouble() * 450.0,
                        electricBrakeForceNewtons = random.nextDouble() * 70000.0,
                        tractionForceNewtons = random.nextDouble() * 100000.0,
                        commandAgeSeconds = random.nextDouble(),
                        previousDemand = listOf(-1.0, 0.0, 1.0)[random.nextInt(3)]
                    )
                })
            )
            val first = order[random.nextInt(order.size)]
            val subsequent = mutableListOf<String>()
            var handleIndex = order.indexOf(first)
            do {
                handleIndex = (handleIndex - 1).coerceAtLeast(0)
                subsequent += order[handleIndex]
            } while (handleIndex != 0)
            val firstDelta = maximumDelta * listOf(0.25, 0.5, 1.0)[case % 3]
            val preview = TrainDynamics.PreviewSession(seed, activeProfile, inputs, 9.80665, firstDelta)
            val response = ServiceBrakingResponse.bound(preview, first, subsequent, maximumDelta)
            assertTrue(response.proven, "case=$case")
            val force = TrainDynamics.steadyServiceBrakeForce(activeProfile, controls, 9.80665) +
                controls.sumOf { it.mass * 9.80665 * grade }
            val deceleration = force / controls.sumOf { it.effectiveMass }
            val peakBound = seed.speed + response.speedGainMetersPerSecond
            val stopBound = response.distanceMeters + peakBound * peakBound / (2.0 * deceleration)
            repeat(5) { partition ->
                var state = seed
                var distance = 0.0
                var tick = 0
                do {
                    val handle = if (tick == 0) first else subsequent[(tick - 1).coerceAtMost(subsequent.lastIndex)]
                    val delta = if (tick == 0) firstDelta else when (partition) {
                        0 -> maximumDelta * 0.25
                        1 -> maximumDelta * 0.5
                        2 -> maximumDelta
                        3 -> if (tick % 2 == 0) maximumDelta * 0.25 else maximumDelta
                        else -> maximumDelta * (0.025 + random.nextDouble() * 0.975)
                    }
                    val result = TrainDynamics.step(state, activeProfile, inputs, handle, 9.80665, delta)
                    distance += result.distance
                    state = result.state
                    assertTrue(state.speed <= peakBound + 1e-8,
                        "peak case=$case partition=$partition tick=$tick speed=${state.speed} bound=$peakBound")
                    assertTrue(distance <= stopBound + 1e-8,
                        "distance case=$case partition=$partition tick=$tick actual=$distance bound=$stopBound")
                    tick++
                } while ((state.speed > 0.0 || tick < subsequent.size + 30) && tick < 20000)
                assertEquals(0.0, state.speed, "case=$case partition=$partition")
            }
        }
    }

    @Test
    fun `held rest response introduces no fictional movement or dead zone`() {
        val controls = listOf(car(), car(false))
        val seed = TrainDynamics.initialState(controls)
        val preview = TrainDynamics.PreviewSession(seed, profile,
            controls.map { TrainDynamics.CarInput(it) }, 9.80665, 0.2)
        val response = ServiceBrakingResponse.bound(preview, "P1",
            listOf("N", "B1", "B2", "B3", "B4", "B5", "B6", "B7"), 0.2)
        assertTrue(response.proven)
        assertEquals(0.0, response.distanceMeters)
        assertEquals(0.0, response.speedGainMetersPerSecond)
    }

}
