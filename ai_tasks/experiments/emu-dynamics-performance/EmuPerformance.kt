package experiment

import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import work.art1st.javarailways.basic.control.TrainDynamics
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import kotlin.math.*

/** Isolated engineering-cost prototype, NOT a calibrated vehicle or a production ATO. */
object EmuPerformance {
    private const val dt = 0.05
    private const val gravity = 9.80665
    @Volatile private var sink = 0.0
    private class Profile : TrainDynamicsProfile {
        override val maxSpeed = 40.0
        override val powerHandles = (1..4).map { "P$it" }
        override val brakeHandles = (1..7).map { "B$it" } + "EB"
        override val brakeApplyTime = 1.2
        override val brakeReleaseTime = 1.8
        override val adhesionCoefficient = 0.2
        override val tractionJerk = 0.8
        override val brakeJerk = 0.8
        override fun normalizedPowerScale(handle: String) = if (handle.startsWith("P")) handle.drop(1).toDouble()/4 else 0.0
        override fun normalizedBrakeScale(handle: String) = when {
            handle == "EB" -> 1.25
            handle.startsWith("B") -> handle.drop(1).toDouble()/7
            else -> 0.0
        }
        override fun resistanceForceAt(speed: Double) = 400.0 + 10.0*speed + 1.2*speed*speed
    }
    private class Car(private val motor: Boolean) : CarDynamicsProfile {
        override val mass = 32000.0
        override val effectiveMass = mass*1.08
        override val motorAdhesionMassRatio = if (motor) 1.0 else 0.0
        override val brakeMaxForce = 45000.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = if (motor) min(50000.0, 500000.0/max(speed,0.5)) else 0.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double)->Double) = defaultResistance(speed)
    }
    private data class State(val motion: TrainDynamics.State, val pressure: DoubleArray, val electric: DoubleArray,
                             val traction: DoubleArray, val age: DoubleArray, val previousDemand: DoubleArray)
    private data class Result(val state: State, val distance: Double, val netForce: Double)
    private enum class Model(val label: String) {
        BASELINE("baseline"), PROTOTYPE("prototype"), FRESH_ARRAYS("fresh-arrays"), REUSED_ARRAYS("reused-arrays")
    }
    /** A Result in REUSED_ARRAYS borrows one bank until a later step reuses it. Retain only deep copies. */
    private class Arrays(n: Int) {
        val pressure=DoubleArray(n); val electric=DoubleArray(n); val traction=DoubleArray(n)
        val age=DoubleArray(n); val previousDemand=DoubleArray(n)
        fun overlaps(s: State) = pressure===s.pressure || electric===s.electric || traction===s.traction ||
            age===s.age || previousDemand===s.previousDemand
    }
    private class Engine(val n: Int, val model: Model, grades: List<Double>? = null) {
        private val complex get() = model != Model.BASELINE
        // Workspace belongs to this Engine only, never to a committed/seed State or another Engine.
        private val first = if(model==Model.REUSED_ARRAYS) Arrays(n) else null
        private val second = if(model==Model.REUSED_ARRAYS) Arrays(n) else null
        val profile = Profile()
        val cars = List(n) { TrainDynamics.CarInput(Car(it%2==0), grades?.get(it) ?: if(it%3==0) -0.01 else 0.005) }
        fun step(s: State, handle: String): Result {
            // Keep both original measured paths byte-for-byte in their arithmetic/dataflow.
            if(model==Model.BASELINE || model==Model.PROTOTYPE) return originalArithmetic(s,handle)
            val p: DoubleArray; val e: DoubleArray; val t: DoubleArray
            val age: DoubleArray; val last: DoubleArray
            when(model) {
                Model.FRESH_ARRAYS -> {
                    p=DoubleArray(n); e=DoubleArray(n); t=DoubleArray(n)
                    age=DoubleArray(n); last=DoubleArray(n)
                }
                Model.REUSED_ARRAYS -> {
                    val firstBank=requireNotNull(first)
                    val destination=if(firstBank.overlaps(s)) requireNotNull(second) else firstBank
                    check(!destination.overlaps(s)) { "source and destination arrays overlap" }
                    p=destination.pressure; e=destination.electric; t=destination.traction
                    age=destination.age; last=destination.previousDemand
                }
                Model.BASELINE, Model.PROTOTYPE -> error("original mode returned before optimized arithmetic")
            }
            val speed=s.motion.speed
            val brake=profile.normalizedBrakeScale(handle)
            val power=profile.normalizedPowerScale(handle)
            var force=0.0; var mass=0.0
            for(i in cars.indices) {
                val c=cars[i].control
                val demand=if(brake>0) -brake else power
                age[i]=if(demand!=s.previousDemand[i]) 0.0 else s.age[i]+dt
                last[i]=demand
                val enabled=age[i]>=0.10
                val adhesion=c.mass*gravity*profile.adhesionCoefficient
                // Electric brake fades below 3m/s, half the cars are trailers.
                val eTarget=if(enabled && brake>0 && i%2==0 && handle!="EB")
                    min(35000.0,400000.0/max(speed,0.5))*min(1.0,speed/3.0)*min(brake,1.0) else 0.0
                e[i]=approach(s.electric[i],eTarget,100000.0*dt)
                val pressureTarget=if(enabled) ((c.brakeMaxForce*brake-e[i]).coerceAtLeast(0.0)/c.brakeMaxForce*350.0) else s.pressure[i]
                p[i]=approach(s.pressure[i],pressureTarget,(if(pressureTarget>s.pressure[i])350.0/1.2 else 350.0/1.8)*dt)
                val tTarget=if(enabled && power>0 && p[i]<5.0 && e[i]<1.0)c.motorForceAt(speed)*power else 0.0
                t[i]=approach(s.traction[i],tTarget,(if(tTarget>s.traction[i])80000.0 else 160000.0)*dt)
                val braking=min(adhesion,e[i]+c.brakeMaxForce*p[i]/350.0)
                force+=min(adhesion,t[i])-braking-profile.resistanceForceAt(speed)-c.mass*gravity*cars[i].grade
                mass+=c.effectiveMass
            }
            val target=if(speed==0.0) max(0.0,force/mass) else force/mass
            val a=approach(s.motion.acceleration,target,0.8*dt)
            val v=(speed+a*dt).coerceIn(0.0,profile.maxSpeed)
            val motion=TrainDynamics.State(v,(v-speed)/dt,p.average()/350.0)
            return Result(State(motion,p,e,t,age,last),(speed+v)*0.5*dt,force)
        }
        // Original measured baseline/prototype plus independent oracle for the rewritten array kernel.
        fun originalArithmetic(s: State, handle: String): Result {
            if (!complex) {
                val r = TrainDynamics.step(s.motion,profile,cars,handle,gravity,dt)
                return Result(s.copy(motion=r.state),r.distance,r.tractionForce-r.brakeForce-r.resistanceForce-r.gradeForce)
            }
            // Deep-owned arrays: every speculative branch has isolated device state.
            val p=s.pressure.copyOf(); val e=s.electric.copyOf(); val t=s.traction.copyOf()
            val age=s.age.copyOf(); val last=s.previousDemand.copyOf()
            val speed=s.motion.speed
            val brake=profile.normalizedBrakeScale(handle)
            val power=profile.normalizedPowerScale(handle)
            var force=0.0; var mass=0.0
            for(i in cars.indices) {
                val c=cars[i].control
                val demand=if(brake>0) -brake else power
                age[i]=if(demand!=last[i]) 0.0 else age[i]+dt
                last[i]=demand
                val enabled=age[i]>=0.10
                val adhesion=c.mass*gravity*profile.adhesionCoefficient
                // Electric brake fades below 3m/s, half the cars are trailers.
                val eTarget=if(enabled && brake>0 && i%2==0 && handle!="EB")
                    min(35000.0,400000.0/max(speed,0.5))*min(1.0,speed/3.0)*min(brake,1.0) else 0.0
                e[i]=approach(e[i],eTarget,100000.0*dt)
                val pressureTarget=if(enabled) ((c.brakeMaxForce*brake-e[i]).coerceAtLeast(0.0)/c.brakeMaxForce*350.0) else p[i]
                p[i]=approach(p[i],pressureTarget,(if(pressureTarget>p[i])350.0/1.2 else 350.0/1.8)*dt)
                val tTarget=if(enabled && power>0 && p[i]<5.0 && e[i]<1.0)c.motorForceAt(speed)*power else 0.0
                t[i]=approach(t[i],tTarget,(if(tTarget>t[i])80000.0 else 160000.0)*dt)
                val braking=min(adhesion,e[i]+c.brakeMaxForce*p[i]/350.0)
                force+=min(adhesion,t[i])-braking-profile.resistanceForceAt(speed)-c.mass*gravity*cars[i].grade
                mass+=c.effectiveMass
            }
            val target=if(speed==0.0) max(0.0,force/mass) else force/mass
            val a=approach(s.motion.acceleration,target,0.8*dt)
            val v=(speed+a*dt).coerceIn(0.0,profile.maxSpeed)
            val motion=TrainDynamics.State(v,(v-speed)/dt,p.average()/350.0)
            return Result(State(motion,p,e,t,age,last),(speed+v)*0.5*dt,force)
        }
        /** Deliberately corrupt the unused bank to catch any field not overwritten by the kernel. */
        fun poisonDestinationForValidation(s: State) {
            if(model!=Model.REUSED_ARRAYS) return
            val firstBank=requireNotNull(first)
            val destination=if(firstBank.overlaps(s)) requireNotNull(second) else firstBank
            destination.pressure.fill(Double.NaN); destination.electric.fill(Double.NaN)
            destination.traction.fill(Double.NaN); destination.age.fill(Double.NaN); destination.previousDemand.fill(Double.NaN)
        }
        private fun requireOwnedSeed(s: State) {
            require(first?.overlaps(s)!=true && second?.overlaps(s)!=true) {
                "selection/recovery seed must be deep-owned; reused step results are borrowed"
            }
        }
        fun seed(kind: String): State {
            val speed=when(kind){"release"->0.0;"brake"->25.0;else->15.0}
            val b=if(kind=="release")1.0 else 0.0
            return State(TrainDynamics.State(speed,if(kind=="accelerate")0.4 else 0.0,b),
                DoubleArray(n){350*b},DoubleArray(n),DoubleArray(n),DoubleArray(n){1.0},
                DoubleArray(n){when(kind){"accelerate","cruise"->1.0;"brake"->-1.0;else->0.0}})
        }
        val order=(1..7).reversed().map{"B$it"}+"N"+(1..4).map{"P$it"}
        private fun toward(current:String,desired:String):String {
            val i=order.indexOf(current);val j=order.indexOf(desired)
            return order[i+(j-i).sign]
        }
        // Structure follows HandleTrainActuator desiredHandle + recovery loop. No Core route/physics cost.
        fun selection(s:State,target:Double):Pair<Double,Int> {
            requireOwnedSeed(s)
            val speed=s.motion.speed
            val desired=if(target>speed+1e-6) {
                (listOf("N")+profile.powerHandles).map{it to step(s,it).state.motion.speed}
                    .filter{it.second<=target+1e-6}.minByOrNull{target-it.second}?.first?:"N"
            } else if(target<speed-1e-6) {
                (listOf("N")+profile.brakeHandles.filter{it!="EB"}).firstOrNull{step(s,it).state.motion.speed<=target+1e-6}?:"B7"
            } else {
                (listOf("N")+profile.powerHandles+profile.brakeHandles.filter{it!="EB"})
                    .minBy{abs(step(s,it).state.motion.speed-target)}
            }
            var handle=toward("N",desired);var count=0
            if(target>=speed) {
                var state=s
                for(k in 0 until 256) {
                    val r=step(state,handle);state=r.state;count++
                    if(state.motion.speed>target+1e-6)break
                    if(handle=="B7" && state.motion.acceleration<=0 && state.motion.brakeScale<=1.0 && r.netForce<=0)break
                    handle=toward(handle,"B7")
                }
            }
            return step(s,toward("N",desired)).state.motion.speed to count
        }
        // Untimed selection oracle from the original workload, using only original arithmetic.
        fun originalSelection(s:State,target:Double):Pair<Double,Int> {
            val speed=s.motion.speed
            val desired=if(target>speed+1e-6) {
                (listOf("N")+profile.powerHandles).map{it to originalArithmetic(s,it).state.motion.speed}
                    .filter{it.second<=target+1e-6}.minByOrNull{target-it.second}?.first?:"N"
            } else if(target<speed-1e-6) {
                (listOf("N")+profile.brakeHandles.filter{it!="EB"}).firstOrNull{originalArithmetic(s,it).state.motion.speed<=target+1e-6}?:"B7"
            } else {
                (listOf("N")+profile.powerHandles+profile.brakeHandles.filter{it!="EB"})
                    .minBy{abs(originalArithmetic(s,it).state.motion.speed-target)}
            }
            var handle=toward("N",desired);var count=0
            if(target>=speed) {
                var state=s
                for(k in 0 until 256) {
                    val r=originalArithmetic(state,handle);state=r.state;count++
                    if(state.motion.speed>target+1e-6)break
                    if(handle=="B7" && state.motion.acceleration<=0 && state.motion.brakeScale<=1.0 && r.netForce<=0)break
                    handle=toward(handle,"B7")
                }
            }
            return originalArithmetic(s,toward("N",desired)).state.motion.speed to count
        }
        fun originalRecovery256(s:State):Double {
            var state=s
            repeat(256) { state=originalArithmetic(state,"B7").state }
            return state.motion.speed+state.motion.brakeScale
        }
        fun recovery256State(s:State):State {
            requireOwnedSeed(s)
            var state=s
            repeat(256) { state=step(state,"B7").state }
            return state
        }
        fun recovery256(s:State):Double {
            val state=recovery256State(s)
            return state.motion.speed+state.motion.brakeScale
        }
    }
    private fun approach(a:Double,b:Double,d:Double)=a+(b-a).coerceIn(-d,d)
    private fun validate() {
        for(model in Model.entries) {
            val complex=model!=Model.BASELINE
            val e=Engine(8,model);val initial=e.seed("release");var s=initial
            repeat(20){s=e.step(s,"P4").state}
            check(s.motion.speed.isFinite());check(initial.pressure.all{it==350.0})
            if(complex) {
                check(s.motion.speed==0.0) { "traction before pressure release" }
                repeat(120){s=e.step(s,"P4").state};check(s.motion.speed>0.0)
                repeat(1000){s=e.step(s,"B7").state};check(s.motion.speed==0.0)
            }
        }
    }
    /** Shared measured consumption retains every output array; final recovery checksum runs once. */
    private fun checksum(s:State):Double {
        var value=s.motion.speed+s.motion.acceleration+s.motion.brakeScale
        for(i in s.pressure.indices) {
            value+=s.pressure[i];value+=s.electric[i];value+=s.traction[i]
            value+=s.age[i];value+=s.previousDemand[i]
        }
        return value
    }
    private fun snapshot(s:State)=State(s.motion,s.pressure.copyOf(),s.electric.copyOf(),
        s.traction.copyOf(),s.age.copyOf(),s.previousDemand.copyOf())
    private fun exact(actual:Double,expected:Double,context:String) {
        check(actual.toRawBits()==expected.toRawBits()) { "$context: expected $expected, actual $actual" }
    }
    private fun exact(actual:State,expected:State,context:String) {
        exact(actual.motion.speed,expected.motion.speed,"$context speed")
        exact(actual.motion.acceleration,expected.motion.acceleration,"$context acceleration")
        exact(actual.motion.brakeScale,expected.motion.brakeScale,"$context brakeScale")
        fun array(a:DoubleArray,b:DoubleArray,label:String) {
            check(a.size==b.size)
            for(i in a.indices) exact(a[i],b[i],"$context $label[$i]")
        }
        array(actual.pressure,expected.pressure,"pressure");array(actual.electric,expected.electric,"electric")
        array(actual.traction,expected.traction,"traction");array(actual.age,expected.age,"age")
        array(actual.previousDemand,expected.previousDemand,"previousDemand")
    }
    private fun exact(actual:Result,expected:Result,context:String) {
        exact(actual.state,expected.state,context)
        exact(actual.distance,expected.distance,"$context distance")
        exact(actual.netForce,expected.netForce,"$context force")
    }
    /** Assertions and deep-copy retention are outside all warmup/timed measurement cells. */
    private fun validateArrayVariants() {
        val variants=listOf(Model.PROTOTYPE,Model.FRESH_ARRAYS,Model.REUSED_ARRAYS)
        val handles=listOf("N","P1","P2","P3","P4","B1","B2","B3","B4","B5","B6","B7","EB")
        for(n in listOf(4,8,16)) for(gradeCase in 0..2) {
            val grades=List(n) { i -> when(gradeCase){0->0.0;1->if(i%3==0)-0.01 else 0.005;else->if(i%2==0)0.035 else -0.045} }
            val oracle=Engine(n,Model.PROTOTYPE,grades)
            val seeds=listOf("accelerate","cruise","brake","release").map(oracle::seed)+
                listOf(0.0,0.01,0.5,2.99,3.0,3.01).map { speed ->
                    State(TrainDynamics.State(speed,-0.15,0.3),DoubleArray(n){70.0+it},
                        DoubleArray(n){1000.0+it*10},DoubleArray(n){2000.0+it*20},
                        DoubleArray(n){if(it%2==0)0.05 else 0.25},DoubleArray(n){if(it%2==0)-0.5 else 0.25})
                }
            for((fixture,seed) in seeds.withIndex()) {
                val initial=snapshot(seed)
                val engines=variants.map { Engine(n,it,grades) }
                val states=engines.map { snapshot(seed) }.toMutableList()
                var reference=snapshot(seed)
                val retained=mutableListOf<Pair<State,State>>()
                val random=java.util.Random(71L+n*100+gradeCase*10+fixture)
                repeat(320) { step ->
                    // Contiguous P/N/B/EB transitions plus reproducible random handle changes.
                    val handle=when(step){in 0..39->"P4";in 40..69->"N";in 70..109->"B4";in 110..139->"EB";else->handles[random.nextInt(handles.size)]}
                    val expected=oracle.originalArithmetic(reference,handle)
                    reference=expected.state
                    for((index,engine) in engines.withIndex()) {
                        val source=states[index]
                        val before=snapshot(source)
                        engine.poisonDestinationForValidation(source)
                        val actual=engine.step(source,handle)
                        exact(source,before,"source isolation ${engine.model} cars=$n fixture=$fixture step=$step")
                        exact(actual,expected,"trajectory ${engine.model} cars=$n grade=$gradeCase fixture=$fixture step=$step")
                        states[index]=actual.state
                        if(step%53==0) retained.add(snapshot(actual.state) to snapshot(reference))
                    }
                }
                exact(seed,initial,"original seed retained")
                retained.forEach { (saved,expected) -> exact(saved,expected,"retained deep copy") }
                for(engine in engines) {
                    // Every candidate reads the exact same seed, independent of prior trials/bank contents.
                    repeat(3) { trial ->
                        val shuffled=handles.shuffled(kotlin.random.Random(n*100+fixture*10+trial))
                        for(handle in shuffled) {
                            engine.poisonDestinationForValidation(seed)
                            exact(engine.step(seed,handle),oracle.originalArithmetic(seed,handle),"branch ${engine.model} $handle")
                            exact(seed,initial,"branch seed remains frozen")
                        }
                    }
                    for(target in listOf(0.0,seed.motion.speed,(seed.motion.speed-0.5).coerceAtLeast(0.0),seed.motion.speed+1.0)) {
                        val expected=oracle.originalSelection(seed,target)
                        val actual=engine.selection(seed,target)
                        exact(actual.first,expected.first,"selection ${engine.model}")
                        check(actual.second==expected.second) { "selection recovery iteration count changed" }
                        exact(seed,initial,"selection seed remains frozen")
                    }
                    var expectedRecovery=snapshot(seed)
                    repeat(256) { expectedRecovery=oracle.originalArithmetic(expectedRecovery,"B7").state }
                    exact(engine.recovery256State(seed),expectedRecovery,"recovery256 full state ${engine.model}")
                    exact(engine.recovery256(seed),oracle.originalRecovery256(seed),"recovery256 ${engine.model}")
                    exact(seed,initial,"recovery seed remains frozen")
                }
            }
            val firstEngine=Engine(n,Model.REUSED_ARRAYS,grades)
            val otherEngine=Engine(n,Model.REUSED_ARRAYS,grades)
            val seed=firstEngine.seed("accelerate")
            val borrowed=firstEngine.step(seed,"B7").state
            val preserved=snapshot(borrowed)
            var other=otherEngine.seed("release")
            repeat(20) { other=otherEngine.step(other,"P4").state }
            exact(borrowed,preserved,"different Engines cannot share workspaces")
            check(runCatching { firstEngine.selection(borrowed,10.0) }.isFailure)
            check(runCatching { firstEngine.recovery256(borrowed) }.isFailure)
            exact(borrowed,preserved,"borrowed workload input is rejected before any writes")
        }
    }
    @JvmStatic fun main(args:Array<String>) {
        validate()
        validateArrayVariants()
        if(args.contains("--validate-only")) {
            println("# array variants validation passed")
            return
        }
        val bean=ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        println("# java=${System.getProperty("java.version")} cpus=${Runtime.getRuntime().availableProcessors()} prototype=uncalibrated")
        println("# consumption=all5arrays_for_step_and_final_recovery,selection_speed_and_iteration_count; reused_results=borrowed")
        println("cars,model,workload,scenario,round,ns_per_op,bytes_per_op,recovery_steps")
        val cells=mutableListOf<Triple<Int,Model,String>>()
        for(n in listOf(4,8,16))for(c in Model.entries)for(w in listOf("step","selection","recovery256"))cells+=Triple(n,c,w)
        // Shuffle removes fixed model/car ordering bias; independent JVMs use distinct order.
        cells.shuffle(java.util.Random(args.firstOrNull()?.toLong()?:1))
        for((n,c,w)in cells) {
            val e=Engine(n,c)
            val scenarios=if(w=="recovery256")listOf("accelerate")else listOf("accelerate","cruise","brake","release")
            for(kind in scenarios) {
                val s=e.seed(kind)
                val target=when(kind){"accelerate"->16.0;"cruise"->15.0;"brake"->14.5;else->5.0}
                val handle=if(kind=="brake")"B7" else "P4"
                val run:()->Double=when(w){
                    "step"->{ {checksum(e.step(s,handle).state)} }
                    "selection"->{ {val selected=e.selection(s,target); selected.first+selected.second.toDouble()} }
                    else->{ {checksum(e.recovery256State(s))} }
                }
                var v=0.0;val warm=System.nanoTime()+300_000_000L
                while(System.nanoTime()<warm){repeat(32){v+=run()}}
                var reps=32
                while(true){val t=System.nanoTime();repeat(reps){v+=run()};if(System.nanoTime()-t>15_000_000 || reps>=1048576)break;reps*=2}
                val steps=if(w=="selection")e.selection(s,target).second else if(w=="recovery256")256 else 0
                repeat(5){round->
                    val a=bean.getThreadAllocatedBytes(Thread.currentThread().id);val t=System.nanoTime()
                    repeat(reps){v+=run()}
                    val elapsed=System.nanoTime()-t;val allocated=bean.getThreadAllocatedBytes(Thread.currentThread().id)-a
                    println("$n,${c.label},$w,$kind,$round,${elapsed.toDouble()/reps},${allocated.toDouble()/reps},$steps")
                }
                sink=v
            }
        }
    }
}
