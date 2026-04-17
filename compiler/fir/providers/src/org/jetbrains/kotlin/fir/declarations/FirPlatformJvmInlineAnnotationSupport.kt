/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.name.ClassId

abstract class FirPlatformJvmInlineAnnotationSupport : FirSessionComponent {
    abstract val jvmInlineAnnotationClassId: ClassId
}

private val FirSession.platformJvmInlineAnnotationSupport: FirPlatformJvmInlineAnnotationSupport?
        by FirSession.nullableSessionComponentAccessor()

val FirSession.jvmInlineAnnotationClassId: ClassId?
    get() = platformJvmInlineAnnotationSupport?.jvmInlineAnnotationClassId
