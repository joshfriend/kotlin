/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.ObsoleteTestInfrastructure
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForSingleModuleProject
import org.jetbrains.kotlin.analyzer.TrackableModuleInfo
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.toVfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.toAbstractProjectFileSearchScope
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.codegen.forTestCompile.TestCompilePaths.KOTLIN_STDLIB_SOURCES_ROOT_PATH
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.fir.renderer.FirRenderer
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import org.jetbrains.kotlin.resolve.jvm.JvmPlatformParameters
import org.jetbrains.kotlin.resolve.jvm.JvmResolverForModuleFactory
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.KotlinTestWithEnvironment
import org.jetbrains.kotlin.test.TestDataAssertions
import org.jetbrains.kotlin.test.TestJdkKind
import java.io.File
import java.util.regex.Pattern

class BuiltInsDeserializationForFirTestCase : KotlinTestWithEnvironment() {
    private val BUILTIN_PACKAGE_NAMES = listOf(
        StandardNames.BUILT_INS_PACKAGE_FQ_NAME,
        StandardNames.COLLECTIONS_PACKAGE_FQ_NAME,
        StandardNames.RANGES_PACKAGE_FQ_NAME
    )

    override fun createEnvironment(): KotlinCoreEnvironment {
        return createEnvironmentWithJdk(ConfigurationKind.JDK_ONLY, TestJdkKind.FULL_JDK)
    }

    @OptIn(ObsoleteTestInfrastructure::class)
    fun testBuiltInPackagesContent() {
        val moduleDescriptor = compileBuiltinsModule(environment)
        val session = FirTestSessionFactoryHelper.createSessionForTests(
            environment.toVfsBasedProjectEnvironment(),
            GlobalSearchScope.allScope(project).toAbstractProjectFileSearchScope()
        )
        for (packageFqName in BUILTIN_PACKAGE_NAMES) {
            val path = ForTestCompileRuntime.transformTestDataPath("compiler/fir/analysis-tests/testData/builtIns").resolve(
                packageFqName.asString().replace('.', '-') + ".txt"
            )
            checkPackageContent(session, packageFqName, moduleDescriptor, path)
        }
    }

    override fun setUp() {
        super.setUp()

        PsiElementFinder.EP.getPoint(project).unregisterExtension(JavaElementFinder::class.java)
    }

    /**
     * Since fir symbol providers can't get all names in package (only fir provider can do it),
     *   we should collect that names from module descriptor from FE 1.0
     */
    @OptIn(SymbolInternals::class)
    private fun checkPackageContent(
        session: FirSession,
        packageFqName: FqName,
        moduleDescriptor: ModuleDescriptor,
        testDataFile: File,
    ) {
        val declarationNames = DescriptorUtils.getAllDescriptors(moduleDescriptor.getPackage(packageFqName).memberScope)
            .mapNotNullTo(sortedSetOf()) { declarations ->
                // When using FastJarFS we might have redundant empty-named subpackages in K1 (see KT-69867)
                declarations.name.takeIf { it.identifier.isNotEmpty() }
            }

        val provider = session.symbolProvider

        val builder = StringBuilder()
        val firRenderer = FirRenderer(builder)

        for (name in declarationNames) {
            for (symbol in provider.getTopLevelCallableSymbols(packageFqName, name)) {
                firRenderer.renderElementAsString(symbol.fir)
                builder.appendLine()
            }
        }

        for (name in declarationNames) {
            val classLikeSymbol = provider.getClassLikeSymbolByClassId(ClassId.topLevel(packageFqName.child(name))) ?: continue
            firRenderer.renderElementAsString(classLikeSymbol.fir)
            builder.appendLine()
        }

        TestDataAssertions.assertEqualsToFile(
            testDataFile,
            builder.toString().trimEnd() + "\n"
        )
    }

    fun compileBuiltinsModule(environment: KotlinCoreEnvironment): ModuleDescriptor {
        val stdlibRoots = System.getProperty(KOTLIN_STDLIB_SOURCES_ROOT_PATH)!!.split(File.pathSeparator)
        val files = KotlinTestUtils.loadToKtFiles(
            environment, ContainerUtil.concat<File>(
                stdlibRoots.map(::allFilesUnder)
            )
        ).filter {
            it.annotationEntries.any { annotation ->
                annotation.shortName == StandardClassIds.Annotations.JvmBuiltin.shortClassName
            }
        }
        return createResolveSessionForFiles(environment.project, files, false).moduleDescriptor
    }

    private fun allFilesUnder(directory: String): List<File?> {
        return FileUtil.findFilesByMask(Pattern.compile(".*\\.kt"), File(directory))
    }

    private fun createResolveSessionForFiles(
        project: Project,
        syntheticFiles: Collection<KtFile>,
        addBuiltIns: Boolean
    ): ResolveSession {
        val projectContext = ProjectContext(project, "lazy resolve test utils")
        val testModule =
            TestModule(project, addBuiltIns)
        val platformParameters = JvmPlatformParameters(
            packagePartProviderFactory = { PackagePartProvider.Empty },
            moduleByJavaClass = { testModule },
            useBuiltinsProviderForModule = { false }
        )

        val resolverForProject = ResolverForSingleModuleProject(
            "test",
            projectContext,
            testModule,
            JvmResolverForModuleFactory(platformParameters, CompilerEnvironment, JvmPlatforms.defaultJvmPlatform),
            GlobalSearchScope.allScope(project),
            syntheticFiles = syntheticFiles
        )

        return resolverForProject.resolverForModule(testModule).componentProvider.get<ResolveSession>()
    }

    private class TestModule(val project: Project, val dependsOnBuiltIns: Boolean) : TrackableModuleInfo {
        override val name: Name = Name.special("<Test module for lazy resolve>")

        override fun dependencies() = listOf(this)
        override fun dependencyOnBuiltIns() =
            if (dependsOnBuiltIns)
                ModuleInfo.DependencyOnBuiltIns.LAST
            else
                ModuleInfo.DependencyOnBuiltIns.NONE

        override val platform: TargetPlatform
            get() = JvmPlatforms.unspecifiedJvmPlatform

        override fun createModificationTracker(): ModificationTracker {
            return ModificationTracker.NEVER_CHANGED
        }

        override val analyzerServices: PlatformDependentAnalyzerServices
            get() = JvmPlatformAnalyzerServices
    }
}
