/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.symbols
import org.jetbrains.kotlin.analysis.test.framework.projectStructure.ktTestModuleStructure
import org.jetbrains.kotlin.analysis.test.framework.services.expressionMarkerProvider
import org.jetbrains.kotlin.psi.KtExperimentalApi
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolution.KtResolvable
import org.jetbrains.kotlin.resolution.KtResolvableCall
import org.jetbrains.kotlin.test.services.TestServices

abstract class AbstractSymbolByReferenceTest : AbstractSymbolTest() {
    @OptIn(KtExperimentalApi::class)
    override fun KaSession.collectSymbols(ktFile: KtFile, testServices: TestServices): SymbolsData {
        val referenceExpression = testServices.expressionMarkerProvider.getBottommostElementOfTypeAtCaret<KtExpression>(ktFile)
        val symbols = when (referenceExpression) {
            is KtResolvableCall -> referenceExpression.resolveCall()?.symbols ?: referenceExpression.resolveSymbols()
            is KtResolvable -> referenceExpression.resolveSymbols()
            else -> error("The references expression must be ${KtResolvable::class.simpleName} or ${KtResolvableCall::class.simpleName}")
        }

        return SymbolsData(symbols.toList())
    }

    override fun getAllowedContainingFiles(mainFile: KtFile, testServices: TestServices): Set<KtFile> {
        // The reference may be from another file, so we need to allow all main test files
        return testServices.ktTestModuleStructure.allMainKtFiles.toSet()
    }
}
