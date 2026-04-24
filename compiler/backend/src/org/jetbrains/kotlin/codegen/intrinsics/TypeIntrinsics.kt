/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.intrinsics

import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics.BEFORE_CHECKCAST_TO_FUNCTION_OF_ARITY
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics.BEFORE_CHECKCAST_TO_FUNCTION_OF_ARITY_DESCRIPTOR
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics.INTRINSICS_CLASS
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics.IS_MUTABLE_COLLECTION_METHOD_DESCRIPTOR
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics.IS_FUNCTION_OF_ARITY_METHOD_NAME
import org.jetbrains.kotlin.codegen.util.inlinecodegen.LightTypeIntrinsics.IS_FUNCTION_OF_ARITY_DESCRIPTOR
import org.jetbrains.kotlin.codegen.util.inlinecodegen.getAsMutableCollectionMethodName
import org.jetbrains.kotlin.codegen.util.inlinecodegen.getFunctionTypeArity
import org.jetbrains.kotlin.codegen.util.inlinecodegen.getIsMutableCollectionMethodName
import org.jetbrains.kotlin.codegen.util.inlinecodegen.getSuspendFunctionTypeArity
import org.jetbrains.kotlin.codegen.util.inlinecodegen.iconstInsnNode
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.tree.*

object TypeIntrinsics {

    /**
     * Returns whether the generation of `is` type check for a given type would require use
     * of intrinsics rather than simple `instanceof`.
     *
     * Shall be in sync with `instanceOf(..)` below
     */
    @JvmStatic
    fun isIntrinsicRequiredForInstanceOf(kotlinType: KotlinType): Boolean =
        getFunctionTypeArity(kotlinType) >= 0 || getSuspendFunctionTypeArity(kotlinType) >= 0 ||
                getClassFqName(kotlinType)?.let { getIsMutableCollectionMethodName(it.asString()) } != null

    @JvmStatic
    fun instanceOf(v: InstructionAdapter, kotlinType: KotlinType, boxedAsmType: Type) {
        LightTypeIntrinsics.instanceOf(getClassFqName(kotlinType)?.asString(), boxedAsmType.internalName) { it.accept(v) }
    }

    @JvmStatic fun instanceOf(instanceofInsn: TypeInsnNode, instructions: InsnList, kotlinType: KotlinType, asmType: Type) {
        val functionTypeArity = getFunctionTypeArity(kotlinType)
        if (functionTypeArity >= 0) {
            instructions.insertBefore(instanceofInsn, iconstInsnNode(functionTypeArity))
            instructions.insertBefore(instanceofInsn,
                                      typeIntrinsicNode(IS_FUNCTION_OF_ARITY_METHOD_NAME, IS_FUNCTION_OF_ARITY_DESCRIPTOR))
            instructions.remove(instanceofInsn)
            return
        }

        val isMutableCollectionMethodName = getClassFqName(kotlinType)?.let { getIsMutableCollectionMethodName(it.asString()) }
        if (isMutableCollectionMethodName != null) {
            instructions.insertBefore(instanceofInsn,
                                      typeIntrinsicNode(isMutableCollectionMethodName, IS_MUTABLE_COLLECTION_METHOD_DESCRIPTOR))
            instructions.remove(instanceofInsn)
            return
        }

        instanceofInsn.desc = asmType.internalName
    }

    @JvmStatic fun checkcast(
            v: InstructionAdapter,
            kotlinType: KotlinType, asmType: Type,
            // This parameter is just for sake of optimization:
            // when we generate 'as?' we do necessary intrinsic checks
            // when calling TypeIntrinsics.instanceOf, so here we can just make checkcast
            safe: Boolean) {
        if (safe) {
            v.checkcast(asmType)
            return
        }

        val functionTypeArity = getFunctionTypeArity(kotlinType)
        if (functionTypeArity >= 0) {
            v.iconst(functionTypeArity)
            v.typeIntrinsic(BEFORE_CHECKCAST_TO_FUNCTION_OF_ARITY, BEFORE_CHECKCAST_TO_FUNCTION_OF_ARITY_DESCRIPTOR)
            v.checkcast(asmType)
            return
        }

        val asMutableCollectionMethodName = getClassFqName(kotlinType)?.let { getAsMutableCollectionMethodName(it.asString()) }
        if (asMutableCollectionMethodName != null) {
            v.typeIntrinsic(asMutableCollectionMethodName, getAsMutableCollectionDescriptor(asmType))
            return
        }

        v.checkcast(asmType)
    }


    private fun getClassFqName(kotlinType: KotlinType): FqName? {
        val classDescriptor = TypeUtils.getClassDescriptor(kotlinType) ?: return null
        return DescriptorUtils.getFqName(classDescriptor).toSafe()
    }

    /**
     * @return function type arity (non-negative), or -1 if the given type is not a function type
     */
    private fun getFunctionTypeArity(kotlinType: KotlinType): Int =
        getFunctionTypeArity(getClassFqName(kotlinType)?.asString() ?: return -1)

    /**
     * @return function type arity (non-negative, not counting continuation), or -1 if the given type is not a function type
     */
    private fun getSuspendFunctionTypeArity(kotlinType: KotlinType): Int =
        getSuspendFunctionTypeArity(getClassFqName(kotlinType)?.asString() ?: return -1)

    private fun typeIntrinsicNode(methodName: String, methodDescriptor: String): MethodInsnNode =
            MethodInsnNode(Opcodes.INVOKESTATIC, INTRINSICS_CLASS, methodName, methodDescriptor, false)

    private fun InstructionAdapter.typeIntrinsic(methodName: String, methodDescriptor: String) {
        invokestatic(INTRINSICS_CLASS, methodName, methodDescriptor, false)
    }


    private val OBJECT_TYPE = Type.getObjectType("java/lang/Object")

    private fun getAsMutableCollectionDescriptor(asmType: Type): String =
            Type.getMethodDescriptor(asmType, OBJECT_TYPE)
}
