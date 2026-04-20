/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(ExperimentalSerializationApi::class)

package org.jetbrains.kotlin.gradle.targets.web

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.gradle.targets.js.NpmVersions
import org.jetbrains.kotlin.gradle.targets.web.KotlinNpmToolingLockFilesTest.Companion.packageLockJson
import org.jetbrains.kotlin.gradle.targets.web.KotlinNpmToolingLockFilesTest.Companion.packageLockJsonContent
import org.jetbrains.kotlin.gradle.targets.web.KotlinNpmToolingLockFilesTest.Companion.yarnLockContent
import org.jetbrains.kotlin.gradle.testing.js.PackageLockJson
import org.jetbrains.kotlin.gradle.testing.prettyPrinted
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import kotlin.test.assertEquals

class KotlinNpmToolingLockFilesTest {

    /**
     * The npm `package-lock.json` file must not have a version.
     *
     * ```json
     * {
     *   "name": "kotlin-npm-tooling",
     *   "packages": {
     *     "": {
     *       "name": "kotlin-npm-tooling",
     *       "version": "...", // forbidden!
     *       "dependencies": { /* ... */ }
     * ```
     *
     * The lockfile is used in a shared directory, which can be used by multiple projects
     * with differing KGP versions.
     * To prevent the `package-lock.json` from changing unnecessarily
     * it should not contain irrelevant data, like the version.
     *
     * There's no need to check `yarn.lock` - it doesn't contain a project version.
     *
     * @see org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinToolingSetupTask
     */
    @Test
    fun `verify npm lockfile does not have project version`() {
        val rootPackage = packageLockJson.packages[""]
        assertNotNull(rootPackage) { "Missing root package in package-lock.json $rootPackage" }

        assertNull(rootPackage.version)
    }

    /**
     * Verify all dependencies in [NpmVersions] are
     */
    @Test
    fun `npm lockfile should contain all dependencies in NpmVersions`() {
        val allNpmVersionsDependencies = NpmVersions().allDependencies.map { it.name }

        val missingElements = allNpmVersionsDependencies
            .filter { it !in packageLockJsonPackages }

        assertTrue(missingElements.isEmpty()) {
            buildString {
                appendLine("Missing ${missingElements.size} dependencies in package-lock.json:")
                appendLine(missingElements)
                appendLine("NpmVersions dependencies: $allNpmVersionsDependencies")
                appendLine("All dependencies in package-lock.json: $packageLockJsonPackages")
            }
        }

        assertLockfileContainsNpmVersionsDependencies(
            lockfileDependencies = packageLockJsonPackages,
            lockfileName = "package-lock.json",
        )
    }

    @Test
    fun `yarn lockfile should contain all dependencies in NpmVersions`() {
        assertLockfileContainsNpmVersionsDependencies(
            lockfileDependencies = yarnLockPackages,
            lockfileName = "yarn.lock",
        )
    }

    private fun assertLockfileContainsNpmVersionsDependencies(
        lockfileDependencies: List<String>,
        lockfileName: String,
    ) {
        val allNpmVersionsDependencies = NpmVersions().allDependencies.map { it.name }

        val missingElements = allNpmVersionsDependencies
            .filter { it !in lockfileDependencies }

        assertTrue(missingElements.isEmpty()) {
            buildString {
                appendLine("Missing ${missingElements.size} dependencies in $lockfileName:")
                appendLine(missingElements)
                appendLine("NpmVersions dependencies: $allNpmVersionsDependencies")
                appendLine("All dependencies in $lockfileName: $lockfileDependencies")
            }
        }
    }

    /**
     * Check all dependencies in [NpmVersions] are present in [packageLockJson],
     * with the expected version.
     */
    @Test
    fun `expect all dependencies in NpmVersions contain the resolved versions in npm lockfile`() {
        val mapActualToExpected =
            NpmVersions().allDependencies
                // I don't know if there's a sensible way to check github versions?
                .filter { !it.version.startsWith("github:") }
                .associate { d ->
                    val actual = "${d.name} : ${d.version}"
                    val expected = packageLockJson.packages["node_modules/${d.name}"]?.let { pkg ->
                        "${d.name} : ${pkg.version}"
                    }
                    actual to expected
                }

        val mismatches = mapActualToExpected.filter { it.key != it.value }

        assertTrue(mismatches.isEmpty()) {
            buildString {
                appendLine("Found mismatches between NpmVersions and package-lock.json:")
                append(mismatches.entries.joinToString("\n") { (actual, expected) -> "$actual != $expected" })
            }
        }
    }

    @Test
    fun `verify transitive dependencies are the same`() {
        // load package-lock.json and yarn.lock and check all transitive dependencies are the same (ignore version)
        assertEquals(
            yarnLockPackages.prettyPrinted,
            packageLockJsonPackages.prettyPrinted,
        )
    }

    companion object {
        /** `package-lock.json` file for KGP's tooling dependencies. */
        private val packageLockJsonContent: String by lazy {
            loadResource("/org/jetbrains/kotlin/gradle/targets/js/npm/package-lock.json")
        }

        /** Parsed [packageLockJsonContent]. */
        private val packageLockJson: PackageLockJson by lazy {
            json.decodeFromString(PackageLockJson.serializer(), packageLockJsonContent)
        }

        /** `yarn.lock` file for KGP's tooling dependencies. */
        private val yarnLockContent: String by lazy {
            loadResource("/org/jetbrains/kotlin/gradle/targets/js/yarn/yarn.lock")
        }

        /** All (non-nested) dependencies in [packageLockJson]. */
        private val packageLockJsonPackages: List<String> by lazy {
            packageLockJson.packages.keys
                .asSequence()
                .filter { it.startsWith("node_modules/") }
                .filter { "/node_modules/" !in it }
                .map { it.substringAfter("node_modules/") }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }

        /** All dependencies in [yarnLockContent]. */
        private val yarnLockPackages: List<String> by lazy {
            yarnLockContent
                // Yarn lock entries are separated by a blank line
                .split("\n\n")
                .asSequence()
                // skip entries that are comments or whitespace only
                .filter { entry ->
                    entry.lines()
                        .map { it.trim() }
                        .any {
                            !it.startsWith("#") && it.isNotBlank()
                        }
                }
                .map { it.trim() }
                .map { entry ->
                    // the first line of an entry is all versions of the package
                    val requestedPkgs = entry.lines().first()

                    val requestedPkgNames =
                        requestedPkgs
                            .removeSuffix(":")
                            .split(", ")
                            .map {
                                it
                                    .removeSurrounding("\"")
                                    // Remove the version.
                                    .substringBeforeLast("@")
                                    // Remove actual package source, if present, to get the alias name.
                                    // We need to compare yarn.lock against package-lock.json,
                                    // and package-lock.json only contains the aliased name.
                                    .substringBeforeLast("@npm:")
                            }
                            .distinct()

                    requestedPkgNames.singleOrNull()
                        ?: error(
                            "Expected a single package name, but got $requestedPkgNames. " +
                                    "Entry:\n$entry"
                        )
                }
                .sorted()
                .distinct()
                .toList()
        }

        private fun loadResource(path: String): String {
            this::class.java.getResourceAsStream(path).use { source ->
                requireNotNull(source) { "Resource not found: $path" }
                return source.bufferedReader().readText()
            }
        }

        private val json: Json = Json {
            ignoreUnknownKeys = true
        }
    }
}
