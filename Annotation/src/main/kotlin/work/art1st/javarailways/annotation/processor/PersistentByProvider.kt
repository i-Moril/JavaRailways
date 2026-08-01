package work.art1st.javarailways.annotation.processor

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import work.art1st.javarailways.annotation.commitBuffer.PersistentByProcessor

@AutoService(SymbolProcessorProvider::class)
class PersistentByProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return PersistentByProcessor(environment.codeGenerator, environment.logger)
    }
}