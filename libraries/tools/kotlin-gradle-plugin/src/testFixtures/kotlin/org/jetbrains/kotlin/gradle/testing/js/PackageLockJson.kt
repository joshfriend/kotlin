/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.testing.js

import kotlinx.serialization.Serializable

@Serializable
internal data class PackageLockJson(
    val name: String,
    val version: String? = null,
    val packages: Map<String, Package>,
) {
    @Serializable
    data class Package(
        val version: String? = null,
        val dependencies: Map<String, String> = emptyMap(),
    )
}
