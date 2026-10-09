package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Builds immutable map files off-thread and serves train state only from published frame snapshots. */
class RailMapPreviewService(private val mapDirectory: File) : AutoCloseable {
    data class RailSource(val id:String,val curve:PrimeCurve)
    data class MarkerSource(val id:String,val label:String,val kind:String,val railId:String,val arc:Double)
    data class SourceSnapshot(val worldName:String,val runtimeId:String,val signature:String,val rails:List<RailSource>,val markers:List<MarkerSource>)
    private data class CachedMap(val signature:String,val content:String)

    private val worlds=ConcurrentHashMap<String,CoreWorld>()
    private val maps=ConcurrentHashMap<String,CachedMap>()
    private val lifecycleLock=Any()
    private val executor=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(2),
        { task->Thread(task,"JavaRailways-rail-map-writer").apply{isDaemon=true} },ThreadPoolExecutor.AbortPolicy())

    /** Must be called in simulation context; only references/IDs are copied here, never sampled. */
    fun capture(worldName:String,world:CoreWorld):SourceSnapshot {
        synchronized(lifecycleLock){worlds[worldName]=world}
        val liveRails=world.allRails
        if(liveRails.size>MAX_RAILS) throw HttpFailure(413,"rail map exceeds $MAX_RAILS rails")
        val rails=liveRails.map { RailSource(it.id.toString(),it.curve) }
        val destinations=world.simpleAtoWorldDataOrNull?.destinations?.all().orEmpty().map { destination ->
            val role=destination.role
            // Operational roles own canonical IDs (station@berth / depot@stabling); do not leak the
            // transient W<n> display name assigned before the role dialog was saved.
            MarkerSource(boundedText(destination.id),boundedText(destination.id),when(role){
                is DestinationRole.StationBerthRole->"STATION"
                is DestinationRole.DepotStablingPointRole->"DEPOT"
                DestinationRole.Regular->"DESTINATION"
            },destination.railId.toString(),destination.arcLengthInAbMeters)
        }
        val destinationIds=destinations.mapTo(HashSet()){it.id}
        val wayPoints=world.basicWorldData.wayPoints.all().filterNot{it.id in destinationIds}.map {
            MarkerSource(boundedText(it.id),boundedText(it.displayName),"WAYPOINT",it.railId.toString(),it.arcLength)
        }
        val markers=destinations+wayPoints
        if(markers.size>MAX_MARKERS) throw HttpFailure(413,"rail map exceeds $MAX_MARKERS markers")
        val railHash=rails.fold(1){hash,rail->31*hash+java.util.Objects.hash(rail.id,System.identityHashCode(rail.curve),rail.curve.totalArcLength)}
        val markerHash=markers.fold(1){hash,marker->31*hash+java.util.Objects.hash(marker.id,marker.kind,marker.label,marker.arc)}
        val signature="${world.runtimeId}:$railHash:$markerHash"
        return SourceSnapshot(worldName,world.runtimeId,signature,rails,markers)
    }

    fun invalidate(worldName:String) = synchronized(lifecycleLock) {
        worlds.remove(worldName);maps.remove(worldName)
        runCatching { File(mapDirectory,"${safeName(worldName)}.json").delete() }
        Unit
    }

    fun render(snapshot:SourceSnapshot):String {
        maps[snapshot.worldName]?.takeIf{it.signature==snapshot.signature}?.let{return it.content}
        val task=try { executor.submit<String>{ buildMap(snapshot).also { content ->
            ensureActive()
            mapDirectory.mkdirs()
            val target=File(mapDirectory,"${safeName(snapshot.worldName)}.json")
            val temporary=File(mapDirectory,".${target.name}.tmp")
            temporary.writeText(content)
            ensureActive()
            synchronized(lifecycleLock) {
                if(worlds[snapshot.worldName]?.runtimeId!=snapshot.runtimeId) {
                    temporary.delete()
                    throw java.util.concurrent.CancellationException("world unloaded while rail map was generated")
                }
                runCatching { java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    .getOrElse { java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                maps[snapshot.worldName]=CachedMap(snapshot.signature,content)
            }
        } } } catch (_:RejectedExecutionException) { throw HttpFailure(429,"rail map generation queue is full; retry") }
        return try { task.get(30,TimeUnit.SECONDS) } catch (failure:java.util.concurrent.TimeoutException) {
            task.cancel(true); throw HttpFailure(504,"rail map generation timed out")
        } catch (failure:java.util.concurrent.ExecutionException) {
            val cause=failure.cause?:failure
            if(cause is HttpFailure)throw cause
            if(cause is java.util.concurrent.CancellationException)throw HttpFailure(409,cause.message?:"rail map generation cancelled")
            throw HttpFailure(500,cause.message?:"rail map generation failed")
        } catch (interrupted:InterruptedException) {
            task.cancel(true);Thread.currentThread().interrupt();throw HttpFailure(503,"rail map generation interrupted")
        }
    }

    fun trains(worldName:String):String {
        val world=worlds[worldName] ?: throw HttpFailure(409,"rail map must be loaded before train streaming")
        val values=ArrayList<kotlinx.serialization.json.JsonObject>()
        var remainingCars=MAX_TOTAL_LIVE_CARS
        var remainingEstimatedBytes=MAX_LIVE_FRAME_BYTES
        for(train in world.allTrains.asSequence().take(MAX_TRAINS)) {
            val frame=train.publishedFrame
            if(remainingCars<=0)break
            val cars=frame.cars.take(minOf(MAX_CARS_PER_TRAIN,remainingCars))
            val trainId=boundedText(frame.trainId);val trainName=boundedText(frame.trainName)
            val estimatedBytes=512+encodedJsonStringBytes(trainId)+encodedJsonStringBytes(trainName)+
                cars.sumOf { 256+encodedJsonStringBytes(boundedText(it.carId)) }
            if(estimatedBytes>remainingEstimatedBytes)break
            remainingCars-=cars.size;remainingEstimatedBytes-=estimatedBytes
            values+=buildJsonObject {
                put("id",trainId);put("name",trainName);put("revision",frame.revision);put("speedMetersPerSecond",frame.speedMetersPerSecond)
                put("derailed",frame.derailed);put("reversed",frame.reversed)
                put("railId",frame.head.railId.toString());put("arcLengthMeters",frame.head.arcLengthInAbMeters)
                put("facing",frame.head.facing.name)
                put("cars",JsonArray(cars.map { car -> buildJsonObject {
                    put("id",boundedText(car.carId));put("lengthMeters",car.lengthMeters)
                    put("x",car.state.positionX);put("y",car.state.positionY);put("z",car.state.positionZ)
                    put("directionX",car.state.directionX);put("directionZ",car.state.directionZ)
                } }))
            }
        }
        val content=JsonArray(values).toString()
        if(content.toByteArray().size>MAX_LIVE_FRAME_BYTES)throw HttpFailure(413,"live rail map train frame exceeds $MAX_LIVE_FRAME_BYTES bytes")
        return content
    }

    private fun buildMap(snapshot:SourceSnapshot):String {
        val totalLength=snapshot.rails.sumOf { it.curve.totalArcLength }
        val interval=maxOf(2.0,totalLength/MAX_TOTAL_POINTS)
        val sampled=HashMap<String,List<MapPoint>>()
        var remainingPoints=MAX_TOTAL_POINTS
        snapshot.rails.forEachIndexed { railIndex,source ->
            ensureActive()
            val minimumForRemaining=(snapshot.rails.size-railIndex-1)*2
            val available=(remainingPoints-minimumForRemaining).coerceAtLeast(2)
            val count=minOf(MAX_POINTS_PER_RAIL,available,maxOf(2,kotlin.math.ceil(source.curve.totalArcLength/interval).toInt()+1))
            val points=ArrayList<MapPoint>(count)
            repeat(count) { index ->
                if(index%64==0) ensureActive()
                val totalArcLength=source.curve.totalArcLength
                val arc=if(index==count-1) totalArcLength else
                    (totalArcLength*index/(count-1).coerceAtLeast(1)).coerceIn(0.0,totalArcLength)
                val point=source.curve.getPointAt(arc).position
                points+=MapPoint(arc,point.x(),point.y(),point.z())
            }
            sampled[source.id]=points
            remainingPoints-=count
        }
        val pointCount=sampled.values.sumOf{it.size}
        val estimatedBytes=pointCount*160L+snapshot.rails.size*192L+snapshot.markers.sumOf { marker ->
            320L+marker.id.length*3L+marker.label.length*3L+marker.kind.length*3L
        }
        if(estimatedBytes>MAX_SERIALIZED_BYTES)throw HttpFailure(413,"estimated rail map exceeds $MAX_SERIALIZED_BYTES bytes")
        val railJson=snapshot.rails.mapIndexed { index,source ->
            if(index%128==0)ensureActive()
            buildJsonObject {
            put("id",source.id);put("elevation",sampled[source.id].orEmpty().map{it.y}.average().takeIf{it.isFinite()}?:0.0)
            put("points",JsonArray(sampled[source.id].orEmpty().map { point -> buildJsonObject {
                put("arc",point.arc);put("x",point.x);put("y",point.y);put("z",point.z)
            } }))
        } }
        val railsById=snapshot.rails.associateBy{it.id}
        val markerJson=snapshot.markers.mapIndexedNotNull { index,marker ->
            if(index%256==0) ensureActive()
            val source=railsById[marker.railId] ?: return@mapIndexedNotNull null
            val point=source.curve.getPointAt(marker.arc.coerceIn(0.0,source.curve.totalArcLength)).position
            buildJsonObject { put("id",marker.id);put("label",marker.label);put("kind",marker.kind);put("railId",marker.railId)
                put("arcLengthMeters",marker.arc);put("x",point.x());put("y",point.y());put("z",point.z()) }
        }
        ensureActive()
        val content=buildJsonObject {
            put("formatVersion",1);put("world",snapshot.worldName);put("runtimeId",snapshot.runtimeId)
            put("generatedAtEpochMillis",System.currentTimeMillis());put("rails",JsonArray(railJson));put("markers",JsonArray(markerJson))
        }.toString()
        if(content.toByteArray().size>MAX_SERIALIZED_BYTES) throw HttpFailure(413,"serialized rail map exceeds $MAX_SERIALIZED_BYTES bytes")
        ensureActive()
        return content
    }

    override fun close(){executor.shutdownNow();synchronized(lifecycleLock){worlds.clear();maps.clear()}}
    private fun boundedText(value:String)=value.take(MAX_TEXT_CHARS)
    private fun encodedJsonStringBytes(value:String)=JsonPrimitive(value).toString().toByteArray().size
    private fun ensureActive(){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("rail map generation cancelled")}
    private fun safeName(value:String)=value.replace(Regex("[^A-Za-z0-9._-]"),"_")
    private data class MapPoint(val arc:Double,val x:Double,val y:Double,val z:Double)
    companion object {
        private const val MAX_RAILS=20_000
        private const val MAX_MARKERS=50_000
        private const val MAX_TRAINS=10_000
        private const val MAX_CARS_PER_TRAIN=256
        private const val MAX_TOTAL_LIVE_CARS=20_000
        private const val MAX_LIVE_FRAME_BYTES=8*1024*1024
        private const val MAX_TOTAL_POINTS=100_000
        private const val MAX_POINTS_PER_RAIL=512
        private const val MAX_SERIALIZED_BYTES=32*1024*1024
        private const val MAX_TEXT_CHARS=256
    }
}
