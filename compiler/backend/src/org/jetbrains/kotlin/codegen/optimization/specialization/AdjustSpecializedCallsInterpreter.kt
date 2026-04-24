/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization.specialization

import org.jetbrains.kotlin.codegen.util.inlinecodegen.isSpecBootstrapCall
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.jetbrains.org.objectweb.asm.tree.analysis.SourceInterpreter
import org.jetbrains.org.objectweb.asm.tree.analysis.SourceValue
import java.util.IdentityHashMap

internal class AdjustSpecializedCallsInterpreter : SourceInterpreter(API_VERSION) {
    val specializedCalls = IdentityHashMap<InvokeDynamicInsnNode, SpecializedCall>()

    override fun naryOperation(
        insn: AbstractInsnNode,
        values: List<SourceValue>,
    ): SourceValue {
        if (insn is InvokeDynamicInsnNode && insn.isSpecBootstrapCall) {
            specializedCalls[insn] = SpecializedCall(insn, values)
        }
        return super.naryOperation(insn, values)
    }
}

internal data class SpecializedCall(val insn: InvokeDynamicInsnNode, val args: List<SourceValue>)
