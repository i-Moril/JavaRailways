package work.art1st.mineRails.basic.timetable.js

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
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
    private var periodTicks: Long? = null
    private val services = mutableListOf<TimetableService>()

    fun setPeriod(ticks: Long): TimetableBuilder {
        if (ticks <= 0) throw TimetableDslException("setPeriod(ticks): ticks 必须 > 0，实际为 $ticks")
        periodTicks = ticks
        return this
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

    fun build(): Timetable = Timetable(id = id, periodTicks = periodTicks, services = services.toList())

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

        val timetable = builder.build()
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
