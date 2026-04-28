/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(ExperimentalAbiValidation::class)

package org.jetbrains.kotlin.gradle.abi

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.create
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.abi.utils.abiValidation
import org.jetbrains.kotlin.gradle.abi.utils.referenceJvmDumpFile
import org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.testbase.*
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@JvmGradlePluginTests
class AbiValidationJvmMavenPublicationsIT : KGPBaseTest() {
    @GradleTest
    fun testNoPublishPluginInJvm(
        gradleVersion: GradleVersion,
    ) {
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            abiValidation {
                binariesSource.set(BinariesSource.MAVEN_PUBLICATIONS)
            }
            buildAndFail("updateKotlinAbi")
        }
    }

    @GradleTest
    fun testSame(
        gradleVersion: GradleVersion,
    ) {
        val compilationsDump: String
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            abiValidation()
            addSampleSource()

            build("updateKotlinAbi")
            compilationsDump = referenceJvmDumpFile().readText()
        }

        val dumpFromPublication: String
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            plugins {
                id("org.gradle.maven-publish")
            }

            abiValidation {
                binariesSource.set(BinariesSource.MAVEN_PUBLICATIONS)
            }

            buildScriptInjection {
                publishing.publications.create<MavenPublication>("maven") {
                    from(this@buildScriptInjection.project.components.getByName("java"))

                    groupId = this@buildScriptInjection.project.group.toString()
                    artifactId = this@buildScriptInjection.project.name
                    version = this@buildScriptInjection.project.version.toString()
                }
            }

            addSampleSource()

            build("updateKotlinAbi")

            dumpFromPublication = referenceJvmDumpFile().readText()
        }

        assertEquals(compilationsDump, dumpFromPublication)
    }

    @GradleTest
    fun testClassifierArtifactsAreIgnored(
        gradleVersion: GradleVersion,
    ) {
        val compilationsDump: String
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            abiValidation()
            addSampleSource()

            build("updateKotlinAbi")
            compilationsDump = referenceJvmDumpFile().readText()
        }

        val dumpFromPublication: String
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            plugins {
                id("org.gradle.maven-publish")
            }

            abiValidation {
                binariesSource.set(BinariesSource.MAVEN_PUBLICATIONS)
            }

            buildScriptInjection {
                val sourcesJar = project.tasks.register("sourcesJar", org.gradle.jvm.tasks.Jar::class.java) { jar ->
                    jar.archiveClassifier.set("sources")
                    jar.from("src/main/kotlin")
                }

                val javadocJar = project.tasks.register("javadocJar", org.gradle.jvm.tasks.Jar::class.java) { jar ->
                    jar.archiveClassifier.set("javadoc")
                }

                publishing.publications.create<MavenPublication>("maven") {
                    from(this@buildScriptInjection.project.components.getByName("java"))
                    artifact(sourcesJar)
                    artifact(javadocJar)

                    groupId = this@buildScriptInjection.project.group.toString()
                    artifactId = this@buildScriptInjection.project.name
                    version = this@buildScriptInjection.project.version.toString()
                }
            }

            addSampleSource()

            build("updateKotlinAbi")
            dumpFromPublication = referenceJvmDumpFile().readText()
        }

        assertEquals(compilationsDump, dumpFromPublication)
    }

    @GradleTest
    fun testFatJarIgnoresDependencyClassesUsedOnlyInternally(
        gradleVersion: GradleVersion,
    ) {
        val compilationsDump: String
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            abiValidation()
            buildScriptInjection {
                project.dependencies.add("implementation", "org.apache.commons:commons-lang3:3.17.0")
            }
            addFatJarSampleSource()

            build("updateKotlinAbi")
            compilationsDump = referenceJvmDumpFile().readText()
        }

        val dumpFromPublication: String
        project(
            "base-kotlin-jvm-library",
            gradleVersion,
        ).run {
            plugins {
                id("org.gradle.maven-publish")
            }

            abiValidation {
                binariesSource.set(BinariesSource.MAVEN_PUBLICATIONS)
            }

            buildScriptInjection {
                project.dependencies.add("implementation", "org.apache.commons:commons-lang3:3.17.0")

                val sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                val mainSourceSet = sourceSets.named("main").get()
                val runtimeClasspath = project.configurations.named("runtimeClasspath")

                project.tasks.named("jar", org.gradle.jvm.tasks.Jar::class.java) { jar ->
                    jar.from(mainSourceSet.output)
                    jar.from(runtimeClasspath.map { files ->
                        files.filter { it.name.endsWith(".jar") }.map { project.zipTree(it) }
                    })
                    jar.duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
                }

                publishing.publications.create<MavenPublication>("fat") {
                    from(this@buildScriptInjection.project.components.getByName("java"))

                    groupId = this@buildScriptInjection.project.group.toString()
                    artifactId = this@buildScriptInjection.project.name
                    version = this@buildScriptInjection.project.version.toString()
                }
            }

            addFatJarSampleSource()

            build("updateKotlinAbi")
            dumpFromPublication = referenceJvmDumpFile().readText()
        }

        assertContains(dumpFromPublication, compilationsDump)
        assertContains(dumpFromPublication, "class PublicAbi")
        assertFalse(dumpFromPublication.contains("fun internalValue"))
    }
}

private fun GradleProject.addSampleSource() {
    kotlinSourcesDir().source("org/jetbrains/tests") { SOURCE_FILE }
}

private fun GradleProject.addFatJarSampleSource() {
    kotlinSourcesDir().source("org/jetbrains/tests/FatJarApi.kt") { FAT_JAR_SOURCE_FILE }
}

private val SOURCE_FILE = """
    fun function() {
        println("Hello, world!")
    }
    
    class Foo {
        fun bar() {
        }
    }
""".trimIndent()

private val FAT_JAR_SOURCE_FILE = """
    import org.apache.commons.lang3.tuple.Pair

    class PublicAbi {
        fun greet(): String = "Hello"
    }

    private val internalPair = Pair.of("left", "right")

    internal fun internalValue(): String = internalPair.left
""".trimIndent()
