/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.testing.js

internal data class YarnLock(
    val entries: List<Entry>,
) {

    data class Entry(
        val name: String,
        val versions: List<String>,
    )

    companion object {
        fun decodeFrom(content: String): YarnLock {
            return YarnLock(
                entries = content
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
                        decodeEntry(entry)
                    }
                    .toList()
            )
        }

        private fun decodeEntry(content: String): Entry {
            // the first line of an entry is all versions of the package
            val requestedPkgs = content.lines().first()

            val entries =
                requestedPkgs
                    .removeSuffix(":")
                    .split(", ")
                    .map { it.removeSurrounding("\"") }
                    .map { entryPackage ->
                        fun invalid(reason: String): Nothing = error("invalid yarn lock entry: $reason. $entryPackage")
                        val r = x.matchEntire(entryPackage)
                            ?: invalid("failed to parse")
                        val name = r.groups["name"]?.value ?: invalid("missing name")
                        val version = r.groups["version"]?.value ?: invalid("missing version")
                        name to version
                    }
                    .groupBy({ it.first }, { it.second })
                    .map { (name, versions) ->
                        Entry(
                            name = name,
                            versions = versions.sorted(),
                        )
                    }

//                    .map { depAndVersion ->
//
//
//                        if (depAndVersion.startsWith("@")) {
//                            depAndVersion.drop(1)
//                        } else {
//                            depAndVersion
//                        }.removeSurrounding("\"")
//
//
//                            .let { depAndVersion ->
//                                val version = depAndVersion.substringBeforeLast("@")
//                                val requestedPkg = depAndVersion.substringBefore("@")
//                            }
//
//
//                        depAndVersion
//                            .removeSurrounding("\"")
//                            // Remove the version.
//                            .substringBeforeLast("@")
//                            // Remove actual package source, if present, to get the alias name.
//                            // We need to compare yarn.lock against package-lock.json,
//                            // and package-lock.json only contains the aliased name.
//                            .substringBeforeLast("@npm:")
//                    }
//                    .distinct()

            return entries.singleOrNull()
                ?: error("Expected a single entry, but got ${entries.size}. Entry:\n$content")
        }

        private val x = Regex(
            """
                    |^(?<name>@?[^@]+)@(?:(?<target>\w+:@?[^@]+)@)?(?<version>.+)$
                    """.trimMargin()
        )
    }
}
