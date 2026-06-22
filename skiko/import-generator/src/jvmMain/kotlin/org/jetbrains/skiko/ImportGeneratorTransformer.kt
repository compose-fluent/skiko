package org.jetbrains.skiko

import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.collections.plus

internal class ImportGeneratorTransformer(private val pluginContext: IrPluginContext) : IrElementTransformerVoid() {

    private val exportSymbols = mutableListOf<String>()
    fun getExportSymbols(): List<String> = exportSymbols

    @Suppress("UNCHECKED_CAST")
    private fun IrAnnotation.getStringValue(value: String): String =
        (getValueArgument(Name.identifier(value)) as IrConst).value as String

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrFunction.makeAnnotation(classId: ClassId, arguments: Map<Name, IrExpression>): IrAnnotation? {
        val annotationClass = pluginContext.referenceClass(classId) ?: return null
        val ctor = annotationClass.owner.constructors.first()

        return IrAnnotationImpl(
            constructorIndicator = null,
            startOffset = startOffset,
            endOffset = endOffset,
            type = annotationClass.owner.defaultType,
            origin = null,
            symbol = ctor.symbol,
            source = SourceElement.NO_SOURCE,
            constructorTypeArgumentsCount = 0,
            classId = classId,
            argumentMapping = arguments,
        ).also {
            it.initializeTargetShapeFromSymbol()
            arguments.entries.forEachIndexed { index, entry ->
                it.arguments[index] = entry.value
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class, DeprecatedForRemovalCompilerApi::class)
    private fun IrFunction.addWasmImportAnnotation(name: String) {
        val moduleName = if (name.startsWith("org_jetbrains_skiko_tests_")) "./skiko-test.mjs" else "./skiko.mjs"

        annotations += makeAnnotation(
            ClassId.fromString("kotlin/wasm/WasmImport"),
            linkedMapOf(
                Name.identifier("module") to IrConstImpl.string(
                    startOffset,
                    endOffset,
                    pluginContext.irBuiltIns.stringType,
                    moduleName
                ),
                Name.identifier("name") to IrConstImpl.string(
                    startOffset,
                    endOffset,
                    pluginContext.irBuiltIns.stringType,
                    name
                ),
            ),
        ) ?: return
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class, DeprecatedForRemovalCompilerApi::class)
    private fun IrFunction.addJsNameAnnotation(name: String) {
        annotations += makeAnnotation(
            ClassId.fromString("kotlin/js/JsName"),
            linkedMapOf(
                Name.identifier("name") to IrConstImpl.string(
                    startOffset,
                    endOffset,
                    pluginContext.irBuiltIns.stringType,
                    name
                ),
            ),
        ) ?: return
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        return super.visitFunction(declaration).apply {
            if (this !is IrFunction) return@apply

            val webImportAnnotation = getAnnotation(FqName("org.jetbrains.skiko.WebImport"))
                ?: return@apply

            val name = webImportAnnotation.getStringValue("name")
            addJsNameAnnotation(name)
            addWasmImportAnnotation(name)

            exportSymbols.add(name)
        }
    }
}
