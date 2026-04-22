/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.references

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.impl.base.references.KaBaseSimpleNameReference
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleOrMultiCall
import org.jetbrains.kotlin.analysis.api.resolution.calls
import org.jetbrains.kotlin.analysis.api.resolution.symbols
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.idea.references.readWriteAccess
import org.jetbrains.kotlin.psi.KtExperimentalApi
import org.jetbrains.kotlin.psi.KtImportAlias
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor
import org.jetbrains.kotlin.resolution.KtResolvableCall
import org.jetbrains.kotlin.resolve.references.ReferenceAccess

internal class KaFirSimpleNameReference(
    expression: KtSimpleNameExpression,
    val isRead: Boolean,
) : KaBaseSimpleNameReference(expression), KaFirReference {
    override fun isReferenceToImportAlias(alias: KtImportAlias): Boolean {
        return super<KaFirReference>.isReferenceToImportAlias(alias)
    }

    @OptIn(KtExperimentalApi::class)
    override fun KaSession.resolveToSymbols(): Collection<KaSymbol> {
        // Resolved calls are preferable for navigation since they provide a more precise location.
        // For instance, it is the case for constructor calls
        return (element as? KtResolvableCall)?.tryResolveCall()
            ?.calls
            ?.flatMap(KaSingleOrMultiCall::symbols)
            ?.takeUnless(List<KaSymbol>::isEmpty)
            ?: element.tryResolveSymbols()?.symbols.orEmpty()
    }

    override fun getResolvedToPsi(analysisSession: KaSession): Collection<PsiElement> = with(analysisSession) {
        val referenceTargetSymbols = resolveToSymbols()
        val psiOfReferenceTarget = super.getResolvedToPsi(analysisSession, referenceTargetSymbols)
        if (psiOfReferenceTarget.isNotEmpty()) return psiOfReferenceTarget
        referenceTargetSymbols.flatMap { symbol ->
            when (symbol) {
                is KaSyntheticJavaPropertySymbol ->
                    if (isRead) {
                        listOfNotNull(symbol.javaGetterSymbol.psi)
                    } else {
                        if (symbol.javaSetterSymbol == null) listOfNotNull(symbol.javaGetterSymbol.psi)
                        else listOfNotNull(symbol.javaSetterSymbol?.psi)
                    }
                else -> listOfNotNull(symbol.psi)
            }
        }
    }

    override fun canBeReferenceTo(candidateTarget: PsiElement): Boolean {
        return true // TODO
    }

    // Extension point used for deprecated Android Extensions. Not going to implement for FIR.
    override fun isReferenceToViaExtension(element: PsiElement): Boolean {
        return false
    }

    override fun getImportAlias(): KtImportAlias? {
        val name = element.getReferencedName()
        val file = element.containingKtFile
        return getImportAlias(file.findImportByAlias(name))
    }

    class Provider : KotlinPsiReferenceProviderContributor<KtSimpleNameExpression> {
        override val elementClass: Class<KtSimpleNameExpression>
            get() = KtSimpleNameExpression::class.java

        override val referenceProvider: KotlinPsiReferenceProviderContributor.ReferenceProvider<KtSimpleNameExpression>
            get() = { nameReferenceExpression ->
                when (nameReferenceExpression.readWriteAccess(useResolveForReadWrite = true)) {
                    ReferenceAccess.READ -> listOf(KaFirSimpleNameReference(nameReferenceExpression, isRead = true))
                    ReferenceAccess.WRITE -> listOf(KaFirSimpleNameReference(nameReferenceExpression, isRead = false))
                    ReferenceAccess.READ_WRITE -> listOf(
                        KaFirSimpleNameReference(nameReferenceExpression, isRead = true),
                        KaFirSimpleNameReference(nameReferenceExpression, isRead = false),
                    )
                }
            }
    }
}
