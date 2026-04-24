// TARGET_BACKEND: JVM
// WITH_STDLIB

@file:OptIn(kotlin.ExperimentalStdlibApi::class)

@JvmInline
value class I(val value: Int)

@JvmInline
value class D(val value: Double)

@JvmInline
value class S(val value: String)

class C(val prompt: String) {
    fun <@JvmSpecialize T> makeHash(x: T) = "$prompt: ${x.hashCode()}"
}

fun <@JvmSpecialize T> id(x: T) = x

fun box(): String {
    if (id("abc") != "abc") return "fail: for string"
    if (id(42) != 42) return "fail: for int"
    if (id(42L) != 42L) return "fail: for long"
    if (id(42.0) != 42.0) return "fail: for double"
    if (id(I(42)) != I(42)) return "fail: for inline backed by int"
    if (id(D(42.0)) != D(42.0)) return "fail: for inline backed by double"
    if (id(S("abc")) != S("abc")) return "fail: for inline backed by string"
    if (!C("hash is").makeHash(42).startsWith("hash is: ")) return "fail: for member method"
    return "OK"
}
