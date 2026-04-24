/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.specialization

import org.jetbrains.kotlin.codegen.util.inlinecodegen.JvmSpecializeMetadataValue
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightIrType
import org.jetbrains.kotlin.codegen.util.inlinecodegen.SpecTypeParametersUsages
import org.jetbrains.kotlin.codegen.util.inlinecodegen.SpecializedTypeAbi
import org.jetbrains.kotlin.codegen.util.inlinecodegen.extractJvmSpecializeMetadataValue
import org.jetbrains.kotlin.codegen.util.inlinecodegen.isSpecBootstrapCall
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.util.TraceClassVisitor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintWriter
import java.lang.invoke.*
import java.util.*
import kotlin.collections.HashMap

public object BootstrapMethods {
//    private val cache = HashMap<String, MethodHandle>()
    private var counter = 0

    /**
     * @param lookup Call site lookup, provided by JVM
     * @param methodName The name of the specialized method
     * @param specializedMethodType The specialized method type
     * @param genericImplClass The class that contains the generic implementation of the specialized method
     * @param genericImplMethodType The method type of the generic implementation of the specialized method
     * @param specTypeParametersUsagesStr TODO
     * @param specializedTypeParametersStr TODO
     */
    @JvmStatic
    public fun bootstrapSpecializedGeneric(
        lookup: MethodHandles.Lookup,
        methodName: String,
        specializedMethodType: MethodType,
        genericImplClass: Class<*>,
        genericImplMethodType: MethodType,
        specTypeParametersUsagesStr: String, // needed for nested calls to avoid expensive reflection
        specializedTypeParametersStr: String,
    ): CallSite {
        val genericImplDesc = genericImplMethodType.toMethodDescriptorString()
        val specializedDesc = specializedMethodType.toMethodDescriptorString()
        val specializedTypeParameters = LightIrType.decodeTypeParameters(specializedTypeParametersStr)

//        val cacheEntryName =
//            lookup.lookupClass().name + "#" + genericImplClass.name + "." + methodName + ":" + genericImplDesc + "#" + specializedTypeParameters

//        cache[cacheEntryName]?.let { return ConstantCallSite(it) }

        val genericClassNode = readClassNode(genericImplClass)

        val genericMethodNode = genericClassNode.methods.find { it.name == methodName && it.desc == genericImplDesc }
            ?: throw RuntimeException("generic method not found: $methodName $genericImplDesc")

        val metadata = genericMethodNode.extractJvmSpecializeMetadataValue()
            ?: error("specialized method is missing the metadata annotation")

        val specializedClassNode = ClassNode().apply {
            this.name = lookup.lookupClass().name.replace('.', '/') + $$"$SpecializedClass$" + (counter++)
            this.version = genericClassNode.version
            this.superName = "java/lang/Object"
            this.access = Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL
        }

        val specializedMethodNode = MethodNode().apply {
            specializedClassNode.methods.add(this)
            this.name = "invoke"
            this.desc = specializedDesc
            this.instructions.add(genericMethodNode.instructions)
            this.tryCatchBlocks = genericMethodNode.tryCatchBlocks
            this.access = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL + Opcodes.ACC_SYNTHETIC
        }

        peeholeAdapt(
            specializedMethodNode,
            specializedMethodType.parameterArray(),
            buildMap {
                for ((k, v) in specializedTypeParameters) {
                    SpecializedTypeAbi.fromLightIrType(v)?.let { put(k, it) }
                }
            },
            specializedTypeParameters,
            metadata,
        )

        val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS).apply { specializedClassNode.accept(this) }
        val bytecode = classWriter.toByteArray()
        if (System.getenv("SPEC_DUMP_CLASS") != null) dumpClass(bytecode)

        val implHandle = defineClass(bytecode, specializedMethodType, lookup)
//        cache[cacheEntryName] = implHandle
        return ConstantCallSite(implHandle)
    }
}

private fun readClassNode(clazz: Class<*>): ClassNode {
    val stream = clazz.classLoader.getResourceAsStream(clazz.name.replace('.', '/') + ".class")
        ?: error("could not get resource stream for class: ${clazz.name}")
    val node = ClassNode()
    ClassReader(readStream(stream)).accept(node, 0)
    return node
}

private fun readStream(inputStream: InputStream): ByteArray {
    inputStream.use { inputStream ->
        ByteArrayOutputStream().use { outputStream ->
            val tmpBuffer = ByteArray(1024 * 4)
            var bytesRead = 0
            var readCount = 0
            while ((inputStream.read(tmpBuffer, 0, tmpBuffer.size).also { bytesRead = it }) != -1) {
                outputStream.write(tmpBuffer, 0, bytesRead)
                readCount++
            }
            outputStream.flush()
            if (readCount == 1) return tmpBuffer
            return outputStream.toByteArray()
        }
    }
}

private fun defineClass(bytecode: ByteArray, specializedImplType: MethodType, callSiteLookup: MethodHandles.Lookup): MethodHandle {
    val javaVersion = System.getProperty("java.version")

    val lookup = when {
        javaVersion.startsWith("1.8.") -> defineClassJdk8(bytecode, callSiteLookup)
        javaVersion.startsWith("11.") -> defineClassJdk11(bytecode, callSiteLookup)
        else -> defineClassJdk17(bytecode, callSiteLookup)
    }

    return lookup.findStatic(lookup.lookupClass(), "invoke", specializedImplType)
}

private fun defineClassJdk8(bytecode: ByteArray, callSiteLookup: MethodHandles.Lookup): MethodHandles.Lookup {
    fun getUnsafeReflectively(unsafeClazz: Class<*>): Any? {
        val f = unsafeClazz.getDeclaredField("theUnsafe")
        f.setAccessible(true)
        return f.get(null)
    }

    val unsafeClazz = Class.forName("sun.misc.Unsafe")
    val unsafe = getUnsafeReflectively(unsafeClazz)
    val defineAnonymousClass = MethodHandles.lookup().findVirtual(
        unsafeClazz,
        "defineAnonymousClass",
        MethodType.methodType(Class::class.java, Class::class.java, ByteArray::class.java, Array::class.java)
    )
    val newClazz = defineAnonymousClass(unsafe, callSiteLookup.lookupClass(), bytecode, null) as Class<*>
    return callSiteLookup.`in`(newClazz)
}

private fun defineClassJdk11(bytecode: ByteArray, callSiteLookup: MethodHandles.Lookup): MethodHandles.Lookup {
    val defineClass = callSiteLookup.javaClass.getDeclaredMethod(
        "defineClass",
        ByteArray::class.java,
    )
    val newClazz = defineClass(callSiteLookup, bytecode) as Class<*>
    return callSiteLookup.`in`(newClazz)
}

private fun defineClassJdk17(bytecode: ByteArray, callSiteLookup: MethodHandles.Lookup): MethodHandles.Lookup {
    return defineClassJdk11(bytecode, callSiteLookup)
//    val classOptClass = Class.forName($$"java.lang.invoke.MethodHandles$Lookup$ClassOption")
//    val classOptArray = java.lang.reflect.Array.newInstance(classOptClass, 0)
//    val defineClass = callSiteLookup.javaClass.getDeclaredMethod(
//        "defineHiddenClass",
//        ByteArray::class.java,
//        Boolean::class.javaPrimitiveType,
//        classOptArray::class.java,
//    )
//    return defineClass(callSiteLookup, bytecode, true, classOptArray) as MethodHandles.Lookup
}

private fun dumpClass(bytecode: ByteArray) {
    println("Dumping class: (${bytecode.size} bytes)")
    val classReader = ClassReader(bytecode)
    classReader.accept(TraceClassVisitor(PrintWriter(System.out)), 0)
}

private val Class<*>.width: Int
    get() = when (this) {
        Long::class.javaPrimitiveType, Double::class.javaPrimitiveType -> 2
        else -> 1
    }

private fun peeholeAdapt(
    methodNode: MethodNode,
    specializedTypes: Array<Class<*>>,
    specializedTypeParameters: Map<Int, SpecializedTypeAbi>,
    typeParameters: Map<Int, LightIrType>,
    metadata: JvmSpecializeMetadataValue,
) {
    fun AbstractInsnNode.isIntrinsic(namePredicate: (String) -> Boolean): Boolean =
        this is MethodInsnNode &&
                this.opcode == Opcodes.INVOKESTATIC &&
                this.owner == "kotlin/jvm/internal/Intrinsics" &&
                namePredicate(this.name)

    fun AbstractInsnNode.isCheckNotNullParameter() = isIntrinsic { it == "checkNotNullParameter" }
    fun AbstractInsnNode.isSpecializedTypeDefaultValueMarker() = isIntrinsic { it.startsWith("specializedTypeDefaultValueMarker") }
    fun AbstractInsnNode.isSpecializedTypeMarker() = isIntrinsic { it.startsWith("specializedTypeMarker") }
    fun AbstractInsnNode.isBoxMarker() = isIntrinsic { it.startsWith("boxMarker") }
    fun AbstractInsnNode.isUnboxMarker() = isIntrinsic { it.startsWith("unboxMarker") }
    fun AbstractInsnNode.isReifiedOperationMarker() = isIntrinsic { it == "reifiedOperationMarker" }

    val varIxdToParamIdx = calcVarIdxToParamIdx(specializedTypes)

    val widenedSlots = metadata.calcWidenedSlots(specializedTypeParameters)

    val instructions = methodNode.instructions

    for (insn in instructions.toArray()) {
        when {
            insn.isCheckNotNullParameter() -> {
                val prev = insn.previous ?: continue
                val prev2 = prev.previous ?: continue
                if (prev2 !is VarInsnNode) continue
                if (prev !is LdcInsnNode) continue
                if (specializedTypes[varIxdToParamIdx[prev2.`var`]!!].isPrimitive) {
                    instructions.set(insn, InsnNode(Opcodes.NOP))
                    instructions.set(prev, InsnNode(Opcodes.NOP))
                    instructions.set(prev2, InsnNode(Opcodes.NOP))
                }
            }

            insn.isSpecializedTypeDefaultValueMarker() -> {
                val typeParameterIndex = (insn as MethodInsnNode).name.substring("specializedTypeDefaultValueMarker".length).toInt()
                instructions.set(insn, InsnNode(specializedTypeParameters[typeParameterIndex]?.defaultOpcode ?: Opcodes.ACONST_NULL))
            }

            insn is VarInsnNode -> {
                // Adjust local variables to account for long and double types in place of specialized generics.
                // These types occupy two slots, so the indices need to be shifted.
                for (slotIndex in widenedSlots.indices) {
                    if (insn.`var` > widenedSlots[slotIndex] + slotIndex) {
                        insn.`var` += 1
                    } else {
                        break
                    }
                }

                if (insn.opcode == Opcodes.ALOAD || insn.opcode == Opcodes.ASTORE) {
                    insn.previous?.takeIf { it.isSpecializedTypeMarker() }?.let { prev ->
                        val typeParameterIndex = (prev as MethodInsnNode).name.substring("specializedTypeMarker".length).toInt()
                        instructions.set(prev, InsnNode(Opcodes.NOP))
                        specializedTypeParameters[typeParameterIndex]?.let {
                            instructions.set(insn, VarInsnNode(insn.opcode - 4 + it.loadStoreReturnOpcodeOffset, insn.`var`))
                        }
                    }
                }
            }

            insn.opcode == Opcodes.ARETURN -> {
                metadata.specTypeParametersUsages.returnType?.adjustType(typeParameters)
                    ?.let { SpecializedTypeAbi.fromLightIrType(it) }
                    ?.let { abi -> instructions.set(insn, InsnNode(Opcodes.IRETURN + abi.loadStoreReturnOpcodeOffset)) }
            }

            insn.isBoxMarker() -> {
                val typeParameterUsage =
                    SpecTypeParametersUsages.Usage.decode((insn as MethodInsnNode).name.substring("boxMarker".length))
                when (val abi = typeParameterUsage.adjustType(typeParameters)?.let { SpecializedTypeAbi.fromLightIrType(it) }) {
                    null -> instructions.set(insn, InsnNode(Opcodes.NOP))
                    else -> abi.genBox(instructions, insn)
                }
            }

            insn.isUnboxMarker() -> {
                val typeParameterUsage =
                    SpecTypeParametersUsages.Usage.decode((insn as MethodInsnNode).name.substring("unboxMarker".length))
                when (val abi = typeParameterUsage.adjustType(typeParameters)?.let { SpecializedTypeAbi.fromLightIrType(it) }) {
                    null -> instructions.set(insn, InsnNode(Opcodes.NOP))
                    else -> abi.genUnbox(instructions, insn)
                }
            }

            insn.isReifiedOperationMarker() -> reify(methodNode, insn as MethodInsnNode, metadata.typeParametersNames, typeParameters)

            insn is InvokeDynamicInsnNode && insn.isSpecBootstrapCall -> {
                val mapping = buildMap {
                    typeParameters.forEach { (genericIndex, typeParameterValue) ->
                        put(metadata.typeParametersNames[genericIndex], typeParameterValue)
                    }
                }
                val nestedSpecTypeParametersUsages = SpecTypeParametersUsages.decode(insn.bsmArgs[2] as String)
                val nestedTypeParameters = LightIrType.decodeTypeParameters(insn.bsmArgs[3] as String).mapValues { it.value.reify(mapping) }
                val descArgs = Type.getArgumentTypes(insn.desc)
                var descReturnType = Type.getReturnType(insn.desc)
                for ((parameterIndex, usage) in nestedSpecTypeParametersUsages.parameterGenericIndices) {
                    if (usage.nullable) error("nullable usages in nested calls are not supported yet")
                    nestedTypeParameters[usage.genericIndex]
                        ?.let { SpecializedTypeAbi.fromLightIrType(it) }
                        ?.let { descArgs[parameterIndex] = Type.getType(it.reprDesc) }
                }
                nestedSpecTypeParametersUsages.returnType
                    ?.let { if (it.nullable) error("nullable usages in nested calls are not supported yet"); nestedTypeParameters[it.genericIndex] }
                    ?.let { SpecializedTypeAbi.fromLightIrType(it) }
                    ?.let { descReturnType = Type.getType(it.reprDesc) }
                insn.desc = Type.getMethodType(descReturnType, *descArgs).descriptor
                insn.bsmArgs[3] = nestedTypeParameters.entries.joinToString("\n") { (k, v) -> "$k=${v.encode()}" }
            }
        }
    }

    instructions.removeAll { it.opcode == Opcodes.NOP }
}

/**
 * Calculate <local variable index> -> <parameter index> mapping
 */
private fun calcVarIdxToParamIdx(parameterTypes: Array<Class<*>>): Map<Int, Int> {
    val varIxdToParamIdx = HashMap<Int, Int>()
    var offset = 0
    for (x in parameterTypes.withIndex()) {
        varIxdToParamIdx[offset] = x.index
        offset += x.value.width
    }
    return varIxdToParamIdx
}

private fun JvmSpecializeMetadataValue.calcWidenedSlots(specializedTypeParameters: Map<Int, SpecializedTypeAbi>): List<Int> {
    val slots = TreeSet<Int>()
    var index = 0
    while (index < specializedSlots.size) {
        val genericIndex = specializedSlots[index++]
        val size = specializedSlots[index++]
        if (specializedTypeParameters[genericIndex]?.isWide == true) {
            repeat(size) { slots.add(specializedSlots[index++]) }
        } else {
            index += size
        }
    }
    return slots.toList()
}
