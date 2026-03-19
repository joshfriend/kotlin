/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.light.classes.symbol.utils

import com.github.benmanes.caffeine.cache.Cache
import org.jetbrains.kotlin.analysis.api.platform.caches.getOrPut

/**
 * A simple wrapper for the nested [Cache] construction.
 *
 * Regular Caffeine cache prohibits nested updates:
 * if some cache is currently computing a value, this cache cannot be updated in this value computation.
 * If [SafeNestedCaffeineCache] gets such a query, it will try to read the already cached value
 * or perform a non-cached computation using the given provider otherwise.
 *
 * @property outerCache the constructed outer cache.
 * @property innerCacheFactory factory for creating nested caches. Is a fallback factory for [getOrPut] on the [outerCache].
 */
internal class SafeNestedCaffeineCache<A : Any, B : Any, C : Any>(
    private val outerCache: Cache<A, Cache<B, C>>,
    private val innerCacheFactory: () -> Cache<B, C>
) {
    private val firstKeysInProgress = ThreadLocal.withInitial { mutableSetOf<A>() }

    fun getOrPut(firstKey: A, secondKey: B, compute: (A, B) -> C?): C? {
        val innerCache = outerCache.getOrPut(firstKey) { innerCacheFactory() }

        if (!firstKeysInProgress.get().add(firstKey)) {
            return innerCache.getIfPresent(secondKey) ?: compute(firstKey, secondKey)
        }

        return try {
            innerCache.get(secondKey) { secondKeyValue ->
                compute(firstKey, secondKeyValue)
            }
        } finally {
            firstKeysInProgress.get().remove(firstKey)
        }
    }

    fun invalidateAll() {
        outerCache.invalidateAll()
    }
}
