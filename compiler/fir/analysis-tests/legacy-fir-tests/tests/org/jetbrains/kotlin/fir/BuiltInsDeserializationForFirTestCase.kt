/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.fir.java.deserialization.FirJvmBuiltinsSymbolProvider
import org.jetbrains.kotlin.fir.renderer.FirRenderer
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirCachingCompositeSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.TestDataAssertions
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.jetbrains.kotlin.util.PerformanceManagerImpl
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.junit.jupiter.api.Test
import java.io.File

class BuiltInsDeserializationForFirTestCase {
    companion object {
        private val BUILTIN_PACKAGE_NAMES = listOf(
            StandardNames.BUILT_INS_PACKAGE_FQ_NAME,
            StandardNames.COLLECTIONS_PACKAGE_FQ_NAME,
            StandardNames.RANGES_PACKAGE_FQ_NAME
        )
    }

    @Test
    fun testBuiltInPackagesContent() {
        val disposable = Disposer.newDisposable()
        try {
            val session = createSession(disposable, TestJdkKind.FULL_JDK)
            for (packageFqName in BUILTIN_PACKAGE_NAMES) {
                val path = ForTestCompileRuntime.transformTestDataPath("compiler/fir/analysis-tests/testData/builtIns").resolve(
                    packageFqName.asString().replace('.', '-') + ".txt"
                )
                checkPackageContent(session, packageFqName, path)
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun createSession(
        rootDisposable: Disposable,
        jdkKind: TestJdkKind,
        configureArguments: K2JVMCompilerArguments.() -> Unit = {},
    ): FirSession {
        val emptyInput = ArgumentsPipelineArtifact(
            arguments = K2JVMCompilerArguments().apply {
                noStdlib = true
                noReflect = true
                noJdk = true
                allowNoSourceFiles = true
                val jdk = when (jdkKind) {
                    TestJdkKind.MOCK_JDK -> KtTestUtil.findMockJdkRtJar()
                    TestJdkKind.MODIFIED_MOCK_JDK -> KtTestUtil.findMockJdkRtModified()
                    TestJdkKind.FULL_JDK_11 -> KtTestUtil.getJdk11Home()
                    TestJdkKind.FULL_JDK_17 -> KtTestUtil.getJdk17Home()
                    TestJdkKind.FULL_JDK_21 -> KtTestUtil.getJdk21Home()
                    TestJdkKind.FULL_JDK -> File(System.getProperty("java.home"))
                }
                classpath = jdk.absolutePath
                configureArguments()
            },
            services = Services.EMPTY,
            rootDisposable,
            GroupingMessageCollector(MessageCollector.NONE, false, false),
            PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "stub for builtins test"),
        )
        val configurationOutput = JvmConfigurationPipelinePhase.executePhase(emptyInput)!!
        val frontendOutput = JvmFrontendPipelinePhase.executePhase(configurationOutput)!!
        return frontendOutput.frontendOutput.outputs.first().session
    }

    @OptIn(SymbolInternals::class)
    private fun checkPackageContent(session: FirSession, packageFqName: FqName, testDataFile: File) {
        val symbolProvider = (session.symbolProvider as FirCachingCompositeSymbolProvider).providers
            .firstIsInstance<FirJvmBuiltinsSymbolProvider>()
        val namesProvider = symbolProvider.symbolNamesProvider
        val classifierNames = namesProvider.getTopLevelClassifierNamesInPackage(packageFqName).orEmpty()
        val callableNames = namesProvider.getTopLevelCallableNamesInPackage(packageFqName).orEmpty()

        val builder = StringBuilder()
        val firRenderer = FirRenderer(builder)

        for (name in callableNames) {
            for (symbol in symbolProvider.getTopLevelCallableSymbols(packageFqName, name)) {
                firRenderer.renderElementAsString(symbol.fir)
                builder.appendLine()
            }
        }

        for (name in classifierNames) {
            val classLikeSymbol = symbolProvider.getClassLikeSymbolByClassId(ClassId.topLevel(packageFqName.child(name))) ?: continue
            firRenderer.renderElementAsString(classLikeSymbol.fir)
            builder.appendLine()
        }

        TestDataAssertions.assertEqualsToFile(
            testDataFile,
            builder.toString().trimEnd() + "\n"
        )
    }
}
