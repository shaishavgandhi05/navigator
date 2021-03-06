package com.shaishavgandhi.navigator

import androidx.annotation.CheckResult
import com.shaishavgandhi.navigator.FileWriter.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import javax.annotation.processing.Filer
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.type.DeclaredType

class ExtensionWriter(private val processingEnvironment: ProcessingEnvironment) {

    val typeUtils = processingEnvironment.typeUtils
    val elementUtils = processingEnvironment.elementUtils
    val messager = processingEnvironment.messager
    val filer: Filer = processingEnvironment.filer

    private val navigatorClass = ClassName.bestGuess("com.shaishavgandhi.navigator.Navigator")
    private val anyClass = ClassName.bestGuess("kotlin.Any")
    private val kotlinArrayList = ClassName("kotlin.collections", "ArrayList")

    /**
     * Java type -> Kotlin type mapper.
     * When we're using annotation processing, the type of the
     * elements still come as Java types. Since we're generating
     * Kotlin extensions, this isn't ideal. Fortunately, we're
     * dealing with a limited set of types with SharedPreferences,
     * so we can map them by hand.
     */
    private val kotlinMapper = hashMapOf(
        "java.lang.String" to ClassName("kotlin", "String"),

        "java.lang.Long" to ClassName.bestGuess("kotlin.Long"),
        "long" to ClassName.bestGuess("kotlin.Long"),
        "long[]" to ClassName.bestGuess("kotlin.LongArray"),
        "java.lang.Long[]" to ClassName.bestGuess("kotlin.LongArray"),

        "int" to ClassName.bestGuess("kotlin.Int"),
        "java.lang.Integer" to ClassName.bestGuess("kotlin.Int"),
        "int[]" to ClassName.bestGuess("kotlin.IntArray"),
        "java.lang.Integer[]" to ClassName.bestGuess("kotlin.IntArray"),

        "boolean" to ClassName.bestGuess("kotlin.Boolean"),
        "java.lang.Boolean" to ClassName.bestGuess("kotlin.Boolean"),
        "boolean[]" to ClassName("kotlin", "BooleanArray"),
        "java.lang.Boolean[]" to ClassName("kotlin", "Array").parameterizedBy(ClassName("kotlin", "Boolean")),

        "float" to ClassName.bestGuess("kotlin.Float"),
        "java.lang.Float" to ClassName.bestGuess("kotlin.Float"),
        "float[]" to ClassName("kotlin", "FloatArray"),
        "java.lang.Float[]" to ClassName("kotlin", "Array").parameterizedBy(ClassName("kotlin", "Float")),

        "java.lang.Double" to ClassName("kotlin", "Double"),
        "double" to ClassName("kotlin", "Double"),
        "double[]" to ClassName("kotlin", "DoubleArray"),
        "java.lang.Double[]" to ClassName("kotlin", "Array").parameterizedBy(ClassName("kotlin", "Double")),

        "byte" to ClassName("kotlin", "Byte"),
        "java.lang.Byte" to ClassName("kotlin", "Byte"),
        "byte[]" to ClassName("kotlin", "ByteArray"),
        "java.lang.Byte[]" to ClassName("kotlin", "Array").parameterizedBy(ClassName("kotlin", "Byte")),

        "short" to ClassName("kotlin", "Short"),
        "java.lang.Short" to ClassName("kotlin","Short"),
        "short[]" to ClassName("kotlin", "ShortArray"),
        "java.lang.Short[]" to ClassName("kotlin","Array").parameterizedBy(ClassName("kotlin", "Short")),

        "char" to ClassName("kotlin", "Char"),
        "java.lang.Character" to ClassName("kotlin", "Char"),
        "char[]" to ClassName("kotlin", "CharArray"),
        "java.lang.Character[]" to ClassName("kotlin", "Array").parameterizedBy(ClassName("kotlin", "Char")),

        "java.lang.CharSequence" to ClassName("kotlin", "CharSequence"),
        "java.lang.CharSequence[]" to ClassName("kotlin", "Array"),
        "java.lang.CharSequence[]" to ClassName("kotlin", "Array")
            .parameterizedBy(ClassName("kotlin", "CharSequence")),
        "java.util.ArrayList<java.lang.CharSequence>" to kotlinArrayList
            .parameterizedBy(ClassName("kotlin", "CharSequence"))
    )

    /**
     * Pre-processes the elements in case they are Parcelable,
     * SparseArray<Parcelable> or Serializable. Since the given
     * elements are actually subtypes of the aforementioned types,
     * we add them to the [kotlinMapper] as well as [typeMapper]
     * to get their types while code generating.
     *
     * @param element to pre-process
     */
    private fun preprocessElement(element: Element) {
        if (isParcelableList(typeUtils, elementUtils, element.asType())) {
            // Add it to the kotlin mapper.

            // Convert the java.util.ArrayList to kotlin.collections.ArrayList
            // Even if the element is declared in Kotlin class, the underlying JVM
            // impl is still java.util.ArrayList and that's what we get.
            val genericType = (element.asType() as DeclaredType).typeArguments[0].asTypeName()
            kotlinMapper[element.asType().toString()] = kotlinArrayList.parameterizedBy(genericType)
        } else if (isSparseParcelableArrayList(typeUtils, elementUtils, element.asType())) {
            // Add to the kotlin mapper.
            kotlinMapper[element.asType().toString()] = element.asType().asTypeName()
        } else if (isParcelableArray(typeUtils, elementUtils, element.asType())) {
            // Add to the kotlin mapper.
            kotlinMapper[element.asType().toString()] = element.asType().asTypeName()
        }
    }

    fun generateExtensions(
        annotations: LinkedHashMap<QualifiedClassName, LinkedHashSet<Element>>,
        classBuilders: List<ClassBuilder>
    ) {
        val annotationsPerClass = LinkedHashMap<QualifiedClassName, LinkedHashSet<Element>>()
        annotationsPerClass.putAll(annotations)
        val kaptGeneratedDirPath = processingEnvironment.options["kapt.kotlin.generated"]
            ?.replace("kaptKotlin", "kapt")
                ?: run {
                    // If the option does not exist this is not being processed by kapt,
                    // so we don't need to generate kotlin extensions
                    return
                }

        if (annotationsPerClass.isEmpty()) {
            return
        }

        // Preprocess elements to add to the Kotlin mapper.
        // TODO: Add this to the FileWriter while processing elements so that we don't do twice the work.
        for (annotation in annotationsPerClass) {
            for (element in annotation.value) {
                preprocessElement(element)
            }
        }

        for (classBuilder in classBuilders) {
            val className = classBuilder.name.kotlinClass

            val extensionFileName = "${className.simpleName}NavigatorExtensions"
            val fileBuilder = FileSpec.builder(className.packageName, extensionFileName)

            val classBinder = ClassName.bestGuess("${className.packageName}.${className.simpleName}Binder")

            fileBuilder.addAnnotation(AnnotationSpec.builder(JvmName::class)
                .addMember("name = \"%L\"", "${className.simpleName}Navigator")
                .build())

            // Static method to add to Navigator
            fileBuilder.addFunction(FunSpec.builder("bind")
                .receiver(navigatorClass)
                .addParameter("binder", className)
                .addStatement("%T.bind(binder)", classBinder)
                .build())

            // Extension on the activity/fragment
            fileBuilder.addFunction(FunSpec.builder("bind")
                .receiver(className)
                .addKdoc(CodeBlock.builder()
                    .addStatement("Extension method on [%T] that binds the variables ", className)
                    .addStatement("in the class annotated with [com.shaishavgandhi.navigator.Extra]")
                    .add("\n")
                    .addStatement("@see %T", classBinder)
                    .build())
                .addStatement("%T.bind(this)", classBinder)
                .build())

            // Replacement for static constructor
            val prepareFunctionBuilder = FunSpec.builder("${className.simpleName.decapitalize()}Builder")
                .receiver(anyClass)
                .addAnnotation(CheckResult::class)
                .returns(ClassName.bestGuess("${className.packageName}.${className.simpleName}Builder"))

            // Builder for return string so we can append parameters to it.
            val returnBuilder = StringBuilder("return ${className.simpleName}Builder.builder(")

            for (param in classBuilder.constructorParams) {
                // Get return type considering Kotlin types
                val returnType: TypeName = if (kotlinMapper[param.type.toString()] != null)
                    kotlinMapper[param.type.toString()]!! else ClassName.bestGuess(param.type.toString())

                // Add as a parameter.
                val elementAnnotations = param.annotations
                    .map { it.toKPoet() }
                    .filter { !it.toString().contains("NonNull") }

                prepareFunctionBuilder.addParameter(ParameterSpec.builder(param.name, returnType)
                    .addAnnotations(elementAnnotations)
                    .build())
                // Add to return statement.
                returnBuilder.append("${param.name}, ")
            }
            // Remove leading ", " from the return statement
            if (classBuilder.constructorParams.isNotEmpty()) {
                returnBuilder.delete(returnBuilder.length - 2, returnBuilder.length)
            }
            returnBuilder.append(")")
            // Add return statement
            prepareFunctionBuilder.addStatement(returnBuilder.toString())
            fileBuilder.addFunction(prepareFunctionBuilder.build())

            fileBuilder.build().writeTo(filer)
        }

    }
}