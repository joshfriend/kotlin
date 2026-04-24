/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization.specialization

import org.jetbrains.kotlin.codegen.util.inlinecodegen.SpecTypeParametersUsages
import org.jetbrains.org.objectweb.asm.tree.analysis.Value

internal data class SpecializationValue(val size_: Int, val genericUsage: SpecTypeParametersUsages.Usage? = null) : Value {
    constructor(genericUsage: SpecTypeParametersUsages.Usage) : this(1, genericUsage)

    override fun getSize() = size_

    override fun toString() = if (genericUsage != null) "special@${genericUsage.encode()}" else "value(size=$size_)"
}
