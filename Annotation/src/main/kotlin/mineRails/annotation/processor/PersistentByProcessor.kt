package work.art1st.mineRails.annotation.commitBuffer

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

class PersistentByProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    val annotationName = "work.art1st.mineRails.core.api.util.persistence.PersistentBy"
    val mapperName = "work.art1st.mineRails.core.api.util.persistence.PersistentMapper"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 1. 查找所有带有 @MyAutoGenerate 的符号（类）
        val symbols = resolver.getSymbolsWithAnnotation(annotationName)
            .filterIsInstance<KSClassDeclaration>()

        symbols.forEach { classDeclaration ->
            generateCode(classDeclaration)
        }

        return emptyList() // 返回无法处理的符号
    }

    private fun getClasses(annotation: KSAnnotation) : List<KSType> {
        // 1. 获取 arguments 列表中的 'cls' 参数
        val clsArgument = annotation.arguments.find { it.name?.asString() == "cls" }

        // 2. vararg 在 KSP 中会被映射为 List<Any?>
        // 因为是 KClass，所以列表里的项是 KSType
        @Suppress("UNCHECKED_CAST")
        val kClasses = clsArgument?.value as? List<KSType>
        return kClasses ?: emptyList()
    }

    private fun generate(mapperType: KSType, typeArgs: List<KSType>): String {
        val logicalType = typeArgs[0]
        val persistentType = typeArgs[1]
        val dependencyType = typeArgs[2]

        val logicalTypeName = logicalType.toTypeName()
        val persistentTypeName = persistentType.toTypeName()
        //val dependencyTypeName = dependencyType.toTypeName()

        return """
            fun $logicalTypeName.saveAs${persistentTypeName}() : $persistentTypeName {
                return ${mapperType.toClassName().simpleName}.save(this)
            }
            
            fun $logicalTypeName.Companion.load(data: $persistentTypeName) : $logicalTypeName {
                return ${mapperType.toClassName().simpleName}.load(data)
            }
            
            val $logicalTypeName.Companion.serializer$persistentTypeName = ${logicalTypeName}Serializer$persistentTypeName
            
            class ${logicalTypeName}Serializer${persistentTypeName} : KSerializer<$logicalTypeName> {
                override val descriptor: SerialDescriptor = $persistentTypeName.serializer().descriptor
            
                override fun serialize(encoder: Encoder, value: $logicalTypeName) {
                    val data = value.saveAs$persistentTypeName
                    encoder.encodeSerializableValue($persistentTypeName.serializer(), data)
                }
            
                override fun deserialize(decoder: Decoder): Foo {
                    val data = decoder.decodeSerializableValue(FooData.serializer())
                    return Foo(id = data.id, name = data.description)
                }
            }
        """.trimIndent()
    }

    private fun generateCode(classDeclaration: KSClassDeclaration) {
        val packageName = classDeclaration.packageName.asString()
        val className = classDeclaration.simpleName.asString()

        // 创建文件输出流
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, classDeclaration.containingFile!!),
            packageName = packageName,
            fileName = className
        )

        val annotation = classDeclaration.annotations.filter {
            it -> it.shortName.asString() == "PersistentBy"
        }.first()

        val classes = getClasses(annotation)

        // 关键步骤：手动拼接字符串
        var code = "package $packageName\n\n"
        classes.forEach {
            val type = resolveBaseGenerics(it, mapperName)
            if (type == null) {
                logger.error("Invalid mapping class ${it.toTypeName()}", classDeclaration)
                return
            }
            code += generate(it, type)
        }

        // 写入并关闭
        file.write(code.toByteArray())
        file.close()
    }

    /**
     * 核心逻辑：获取指定基类在当前类型上下文中的实际泛型参数
     */
    fun resolveBaseGenerics(
        startType: KSType,
        targetBaseFullQualifiedName: String
    ): List<KSType>? {
        val startDeclaration = startType.declaration as? KSClassDeclaration
        startType.arguments[0].type

        startDeclaration.superTypes.first().resolve().arguments.first().type.
// 2. 从所有父类中直接滤出 PersistentMapper
        val persistentMapperType = startDeclaration?.getAllSuperTypes()?.firstOrNull {
            it.declaration.qualifiedName?.asString() == targetBaseFullQualifiedName
        }

// 3. 按照位置（Index）强行取值
        if (persistentMapperType != null) {
            // arguments[0] -> Logical (Foo)
            // arguments[1] -> Persistent (Bar)
            // arguments[2] -> Unit
            val logicalKSType = persistentMapperType.arguments.getOrNull(0)?.type?.resolve()!!
            val persistentKSType = persistentMapperType.arguments.getOrNull(1)?.type?.resolve()!!
            val unitKSType = persistentMapperType.arguments.getOrNull(2)?.type?.resolve()!!

            // 4. 验证结果
            logger.logging("Logical type: ${logicalKSType.declaration.qualifiedName?.asString()}")
            logger.logging("Persistent type: ${persistentKSType.declaration.qualifiedName?.asString()}")
            logger.logging("Unit type: ${unitKSType.declaration.qualifiedName?.asString()}")
            return listOf(logicalKSType, persistentKSType, unitKSType)
        }
        return null
    }
}