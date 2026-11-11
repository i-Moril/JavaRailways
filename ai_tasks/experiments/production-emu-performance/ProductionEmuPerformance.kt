package experiment.productionemu

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.min
import kotlin.random.Random
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.BrakingConstraints
import work.art1st.javarailways.basic.api.ato.BrakingLimit
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

/** Formal production actuator microbenchmark, not a whole-world or calibrated vehicle benchmark. */
object ProductionEmuPerformance {
    private const val DT=0.05
    private const val CYCLE=1200
    @Volatile private var sink=0.0
    private class Profile:TrainDynamics.TrainProfile {
        override val maxSpeed=33.3
        override val powerHandles=listOf("P1","P2","P3","P4","P5")
        override val brakeHandles=listOf("B1","B2","B3","B4","B5","B6","B7","EB")
        override val brakeApplyTime=1.2
        override val brakeReleaseTime=1.8
        override val adhesionCoefficient=0.22
        override val tractionJerk=0.7
        override val brakeJerk=1.0
        override fun normalizedPowerScale(handle:String)=when(handle){"P1"->0.2;"P2"->0.4;"P3"->0.6;"P4"->0.8;"P5"->1.0;else->0.0}
        override fun normalizedBrakeScale(handle:String)=when(handle){"B1"->0.15;"B2"->0.3;"B3"->0.45;"B4"->0.6;"B5"->0.75;"B6"->0.9;"B7"->1.0;"EB"->1.25;else->0.0}
        override fun resistanceForceAt(speed:Double)=900.0+22.0*speed+5.5*speed*speed
    }
    private class Car(index:Int):TrainDynamics.CarProfile {
        private val motor=index%2==1
        override val mass=if(motor)37000.0 else if(index%4==0)34000.0 else 32000.0
        override val effectiveMass=mass*1.08
        override val motorAdhesionMassRatio=1.0
        override val brakeMaxForce=if(motor)52000.0 else 48000.0
        override val brakeAdhesionMassRatio=1.0
        override val electricBrakeMaxForce=if(motor)30000.0 else 0.0
        override val equipment=TrainDynamics.Equipment(commandDelaySeconds=0.08+(index%3)*0.02,
            tractionApplyRateNewtonsPerSecond=80000.0+(index%3)*5000.0)
        override fun motorForceAt(speed:Double)=if(motor)min(90000.0,950000.0/speed.coerceAtLeast(0.5)) else 0.0
        override fun resistanceForceAt(speed:Double,defaultResistance:(Double)->Double)=defaultResistance(speed)
    }
    private fun plannedSpeed(frame:Int):Double=when(frame%CYCLE){
        in 0..79->0.0 // held brake, followed by release and traction buildup
        in 80..399->18.0
        in 400..499->10.0
        in 500..799->0.0 // residual traction, electric fade and pneumatic stop
        in 800..1049->8.0
        else->0.0
    }
    @JvmStatic fun main(args:Array<String>) {
        val seed=args.getOrNull(0)?.toInt()?:11
        val warmup=args.getOrNull(1)?.toInt()?:2400
        val frames=args.getOrNull(2)?.toInt()?:4800
        require(warmup>=CYCLE&&frames>=CYCLE)
        val bean=ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled=true
        val threadId=Thread.currentThread().id
        println("row,seed,cars,mode,frames,mean_ns,p50_ns,p95_ns,p99_ns,bytes_per_frame,moving_frames,max_speed,checksum")
        val cases=(listOf(4,8,16).flatMap { cars->listOf(cars to false,cars to true) }).shuffled(Random(seed))
        for((carCount,envelope) in cases) {
            TestWorldFixture.create().use { fixture->
                val rail=createStraightRailFromSegments(fixture.world,PrimeCurveGenerator.buildStraight(1000.0,1))
                val train=createTrainWithConfig("performance-$carCount",rail,fixture.world,List(carCount){18.0})
                val actuator=HandleTrainActuator(train,Profile(),List(carCount){Car(it)},9.80665)
                // Grade scanning and Core geometry movement are deliberately outside this microbenchmark.
                val segments=listOf(BrakingGradeSegment(40.0,0.0),BrakingGradeSegment(60.0,0.02),
                    BrakingGradeSegment(100.0,-0.015),BrakingGradeSegment(6000.0,0.0))
                var speed=0.0
                var checksum=0.0
                fun advance(frame:Int):Double {
                    val planned=plannedSpeed(frame)
                    val target=if(envelope)min(planned,actuator.brakingEnvelope(segments, 0.05, fixture.world.maximumPhysicsDeltaSeconds)
                        .permittedSpeedMetersPerSecond(0.0,5000.0,speed,0.0)) else planned
                    val constraints=if(envelope)BrakingConstraints(segments,listOf(BrakingLimit(5000.0,0.0,0.0)),
                        fixture.world.maximumPhysicsDeltaSeconds) else null
                    val step=actuator.step(speed,ControlDemand(target,constraints),DT)
                    speed=step.speedMetersPerSecond
                    checksum+=step.distanceMeters+speed
                    return speed
                }
                repeat(warmup){advance(it)}
                val elapsed=LongArray(frames)
                var moving=0
                var maximum=0.0
                val allocatedBefore=bean.getThreadAllocatedBytes(threadId)
                val totalBefore=System.nanoTime()
                repeat(frames) { frame->
                    val before=System.nanoTime()
                    val actual=advance(warmup+frame)
                    elapsed[frame]=System.nanoTime()-before
                    if(actual>0.1)moving++
                    maximum=maxOf(maximum,actual)
                }
                val total=System.nanoTime()-totalBefore
                val allocated=bean.getThreadAllocatedBytes(threadId)-allocatedBefore
                check(checksum.isFinite()&&moving>frames/10&&maximum>2.0){"benchmark did not exercise moving equipment: $carCount/$envelope speed=$maximum moving=$moving"}
                sink=checksum
                elapsed.sort()
                fun percentile(fraction:Double)=elapsed[((frames-1)*fraction).toInt()]
                println("row,$seed,$carCount,${if(envelope)"envelope-and-actuate" else "actuation-only"},$frames,${total.toDouble()/frames},${percentile(.5)},${percentile(.95)},${percentile(.99)},${allocated.toDouble()/frames},$moving,$maximum,$checksum")
            }
        }
        check(sink.isFinite())
    }
}
