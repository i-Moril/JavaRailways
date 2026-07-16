package work.art1st.mineRails.basic.timetable.js

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.depot.DepotTrainSelector
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.timetable.Timetable
import work.art1st.mineRails.basic.timetable.TimetableAction
import work.art1st.mineRails.basic.timetable.TimetableService
import work.art1st.mineRails.basic.timetable.TimetableStep
import work.art1st.mineRails.basic.timetable.TimetableValidationResult
import work.art1st.mineRails.basic.timetable.TimetableValidator
import work.art1st.mineRails.basic.timetable.TrainSelector
import work.art1st.mineRails.core.api.physics.World
import java.io.File

class TimetableDslException(message: String) : IllegalArgumentException(message)

class TimetableBuilder(private val id: String) {
    private var periodDays: Long? = null
    private val services = mutableListOf<TimetableService>()

    fun setPeriodDays(days: Long): TimetableBuilder {
        if (days <= 0L) {
            throw TimetableDslException("setPeriodDays(days): days 必须 > 0，实际为 $days")
        }
        runCatching { Math.multiplyExact(days, Timetable.TICKS_PER_GAME_DAY) }.getOrElse {
            throw TimetableDslException("setPeriodDays(days): 周期 Tick 溢出，实际为 $days days")
        }
        periodDays = days
        return this
    }

    /** Compatibility entry point; new scripts must declare whole logical game days. */
    fun setPeriod(ticks: Long): TimetableBuilder {
        if (ticks <= 0L || ticks % Timetable.TICKS_PER_GAME_DAY != 0L) {
            throw TimetableDslException(
                "setPeriod(ticks): ticks 必须是 ${Timetable.TICKS_PER_GAME_DAY} 的正整数倍；" +
                    "请使用 setPeriodDays(days)"
            )
        }
        return setPeriodDays(ticks / Timetable.TICKS_PER_GAME_DAY)
    }

    fun service(id: String, builder: Value): TimetableBuilder {
        return service(id, null, builder)
    }

    fun service(id: String, selector: Value?, builder: Value): TimetableBuilder {
        if (id.isBlank()) throw TimetableDslException("service(id, ...): id 不能为空")
        if (!builder.canExecute()) throw TimetableDslException("service($id): 第三个参数必须是 function(t) {...}")

        val serviceBuilder = ServiceBuilder(id)
        builder.execute(serviceBuilder)
        services += TimetableService(
            id = id,
            trainSelector = parseTrainSelector(id, selector),
            steps = serviceBuilder.buildSteps()
        )
        return this
    }

    fun build(): Timetable = Timetable(
        id = id,
        periodDays = periodDays
            ?: throw TimetableDslException("时刻表必须调用 setPeriodDays(days) 声明周期"),
        services = services.toList()
    )

    private fun parseTrainSelector(serviceId: String, selector: Value?): TrainSelector {
        if (selector == null || selector.isNull) return TrainSelector.AnyTrain
        if (selector.isString) return TrainSelector.TrainId(selector.asString())
        if (selector.hasArrayElements()) return TrainSelector.WayPointPool(selector.asStringList("service($serviceId) trainSelector"))
        throw TimetableDslException("service($serviceId): trainSelector 只支持字符串 trainId、路径点数组或 null")
    }
}

class ServiceBuilder(private val serviceId: String) {
    private val steps = mutableListOf<TimetableStep>()

    fun at(tick: Long): TimedActionBuilder {
        if (tick < 0) throw TimetableDslException("service($serviceId).at(tick): tick 不能为负，实际为 $tick")
        return TimedActionBuilder(serviceId, tick) { action -> steps += TimetableStep.At(tick, action) }
    }

    fun check(wayPointId: String): ServiceBuilder {
        if (wayPointId.isBlank()) throw TimetableDslException("service($serviceId).check(wayPointId): wayPointId 不能为空")
        steps += TimetableStep.Check(wayPointId)
        return this
    }

    fun buildSteps(): List<TimetableStep> = steps.toList()
}

class TimedActionBuilder(
    private val serviceId: String,
    private val tick: Long,
    private val addAction: (TimetableAction) -> Unit
) {
    fun takeFromDepot(depotId: String): ServiceBuilderLike =
        takeFromDepot(depotId, null)

    fun takeFromDepot(depotId: String, options: Value?): ServiceBuilderLike {
        val context = "service($serviceId).at($tick).takeFromDepot"
        if (depotId.isBlank()) throw TimetableDslException("$context: depotId 不能为空")
        if (options != null && !options.isNull && !options.hasMembers()) {
            throw TimetableDslException("$context: options 必须是对象")
        }
        val members = options?.takeUnless(Value::isNull)?.memberKeys.orEmpty()
        val unknown = members - DEPOT_SELECTOR_OPTIONS
        if (unknown.isNotEmpty()) {
            throw TimetableDslException("$context: 未知 options: ${unknown.sorted().joinToString()}")
        }
        val minimumCarsLong = options.optionalLong("minimumCars", context)
        val maximumCarsLong = options.optionalLong("maximumCars", context)
        if (minimumCarsLong != null && minimumCarsLong !in 1..Int.MAX_VALUE.toLong()) {
            throw TimetableDslException("$context: minimumCars 必须是正整数")
        }
        if (maximumCarsLong != null && maximumCarsLong !in 1..Int.MAX_VALUE.toLong()) {
            throw TimetableDslException("$context: maximumCars 必须是正整数")
        }
        val selector = runCatching {
            DepotTrainSelector(
                depotId = DepotId(depotId),
                requiredTags = options.optionalStringSet("tags", context),
                minimumCars = minimumCarsLong?.toInt(),
                maximumCars = maximumCarsLong?.toInt(),
                minimumSpeedMetersPerSecond = options.optionalDouble(
                    "minimumSpeedMetersPerSecond",
                    context
                ),
                requiredCapabilities = options.optionalStringSet(
                    "capabilities",
                    context
                )
            )
        }.getOrElse { throw TimetableDslException("$context: ${it.message}") }
        addAction(TimetableAction.TakeFromDepot(selector))
        return ServiceBuilderLike
    }

    fun goto(target: Value): ServiceBuilderLike {
        val action = when {
            target.isString -> TimetableAction.GoTo(target.asString())
            target.hasArrayElements() -> TimetableAction.GoVia(target.asStringList("service($serviceId).at($tick).goto"))
            else -> throw TimetableDslException("service($serviceId).at($tick).goto(target): target 必须是路径点字符串或路径点数组")
        }
        addAction(action)
        return ServiceBuilderLike
    }

    fun stop(ticks: Long): ServiceBuilderLike {
        if (ticks <= 0) throw TimetableDslException("service($serviceId).at($tick).stop(ticks): ticks 必须 > 0，实际为 $ticks")
        addAction(TimetableAction.Stop(ticks))
        return ServiceBuilderLike
    }

    fun call(stationId: String, berthId: String): ServiceBuilderLike =
        call(stationId, berthId, null)

    fun call(stationId: String, berthId: String, options: Value?): ServiceBuilderLike {
        val context = "service($serviceId).at($tick).call"
        if (stationId.isBlank() || berthId.isBlank()) {
            throw TimetableDslException("$context: stationId 和 berthId 不能为空")
        }
        if (options != null && !options.isNull && !options.hasMembers()) {
            throw TimetableDslException("$context: options 必须是对象")
        }
        val members = options?.takeUnless(Value::isNull)?.memberKeys.orEmpty()
        val unknown = members - STATION_CALL_OPTIONS
        if (unknown.isNotEmpty()) {
            throw TimetableDslException("$context: 未知 options: ${unknown.sorted().joinToString()}")
        }
        val minimumDwellTicks = options.optionalLong("minimumDwellTicks", context)
        val plannedDepartureTick = options.optionalLong("plannedDepartureTick", context)
        val stoppingOffset = options.optionalDouble("stoppingOffsetMetersBefore", context)
        if (minimumDwellTicks != null && minimumDwellTicks < 0L) {
            throw TimetableDslException("$context: minimumDwellTicks 不能为负")
        }
        if (plannedDepartureTick != null && plannedDepartureTick < tick) {
            throw TimetableDslException("$context: plannedDepartureTick 不能早于 call tick")
        }
        if (stoppingOffset != null && stoppingOffset < 0.0) {
            throw TimetableDslException("$context: stoppingOffsetMetersBefore 不能为负")
        }
        val doorSide = options?.getMember("doorSide")?.takeUnless(Value::isNull)?.let { value ->
            if (!value.isString) throw TimetableDslException("$context: doorSide 必须是字符串")
            runCatching { DoorSide.valueOf(value.asString().uppercase()) }.getOrElse {
                throw TimetableDslException("$context: doorSide 必须是 none/left/right/both")
            }
        }
        addAction(
            TimetableAction.CallStation(
                stationId = stationId,
                berthId = berthId,
                minimumDwellTicks = minimumDwellTicks,
                plannedDepartureTick = plannedDepartureTick,
                stoppingOffsetMetersBefore = stoppingOffset,
                doorSideOverride = doorSide
            )
        )
        return ServiceBuilderLike
    }

    private companion object {
        val DEPOT_SELECTOR_OPTIONS = setOf(
            "tags",
            "minimumCars",
            "maximumCars",
            "minimumSpeedMetersPerSecond",
            "capabilities"
        )
        val STATION_CALL_OPTIONS = setOf(
            "minimumDwellTicks",
            "plannedDepartureTick",
            "stoppingOffsetMetersBefore",
            "doorSide"
        )
    }
}

/** 占位返回值，让 JS 链式调用时得到非 null 对象；后续如需要可扩展。 */
object ServiceBuilderLike

sealed class TimetableScriptLoadResult {
    data class Success(
        val timetable: Timetable,
        val validation: TimetableValidationResult
    ) : TimetableScriptLoadResult()

    data class Failure(val message: String) : TimetableScriptLoadResult()
}

class TimetableScriptLoader(private val world: World) {
    fun load(file: File, timetableId: String = file.nameWithoutExtension): TimetableScriptLoadResult {
        if (!file.exists()) return TimetableScriptLoadResult.Failure("脚本文件不存在: ${file.path}")
        if (!file.isFile) return TimetableScriptLoadResult.Failure("不是脚本文件: ${file.path}")

        val builder = TimetableBuilder(timetableId)
        try {
            Context.newBuilder("js")
                .allowAllAccess(true)
                .option("js.ecmascript-version", "2023")
                .build().use { context ->
                    val source = Source.newBuilder("js", file).name(file.name).build()
                    context.eval(source)
                    val main = context.getBindings("js").getMember("main")
                        ?: return TimetableScriptLoadResult.Failure("脚本缺少入口函数: function main(timetable) {...}")
                    if (!main.canExecute()) {
                        return TimetableScriptLoadResult.Failure("main 不是可执行函数: function main(timetable) {...}")
                    }
                    main.execute(builder)
                }
        } catch (e: TimetableDslException) {
            return TimetableScriptLoadResult.Failure("DSL 错误: ${e.message}")
        } catch (e: PolyglotException) {
            return TimetableScriptLoadResult.Failure(formatPolyglotException(file, e))
        } catch (e: Exception) {
            return TimetableScriptLoadResult.Failure("加载失败: ${e.message ?: e::class.java.simpleName}")
        }

        val timetable = try {
            builder.build()
        } catch (e: TimetableDslException) {
            return TimetableScriptLoadResult.Failure("DSL 错误: ${e.message}")
        }
        val validation = TimetableValidator(world).validate(timetable)
        if (!validation.isSuccess) {
            return TimetableScriptLoadResult.Failure(
                buildString {
                    appendLine("时刻表验证失败:")
                    validation.errors.forEach { appendLine("- $it") }
                }.trimEnd()
            )
        }
        return TimetableScriptLoadResult.Success(timetable, validation)
    }

    private fun formatPolyglotException(file: File, e: PolyglotException): String {
        val location = e.sourceLocation
        val prefix = if (location != null) {
            "${file.name}:${location.startLine}:${location.startColumn}"
        } else {
            file.name
        }
        val kind = if (e.isSyntaxError) "JS 语法错误" else "JS 执行错误"
        return "$kind at $prefix: ${e.message}"
    }
}

private fun Value?.optionalLong(name: String, context: String): Long? {
    val value = this?.getMember(name) ?: return null
    if (value.isNull) return null
    if (!value.fitsInLong()) throw TimetableDslException("$context: $name 必须是整数")
    return value.asLong()
}

private fun Value?.optionalDouble(name: String, context: String): Double? {
    val value = this?.getMember(name) ?: return null
    if (value.isNull) return null
    if (!value.fitsInDouble()) throw TimetableDslException("$context: $name 必须是数字")
    val result = value.asDouble()
    if (!result.isFinite()) throw TimetableDslException("$context: $name 必须是有限数字")
    return result
}

private fun Value?.optionalStringSet(name: String, context: String): Set<String> {
    val value = this?.getMember(name) ?: return emptySet()
    if (value.isNull) return emptySet()
    if (!value.hasArrayElements()) {
        throw TimetableDslException("$context: $name 必须是字符串数组")
    }
    return value.asStringList("$context.$name").toSet()
}

private fun Value.asStringList(context: String): List<String> {
    val result = mutableListOf<String>()
    for (index in 0 until arraySize) {
        val item = getArrayElement(index)
        if (!item.isString) throw TimetableDslException("$context[$index] 必须是字符串")
        val value = item.asString()
        if (value.isBlank()) throw TimetableDslException("$context[$index] 不能为空")
        result += value
    }
    return result
}
