// CHECK_ASM_LIKE_INSTRUCTIONS
// CURIOUS_ABOUT: f
// WITH_STDLIB

fun <T> f() = kotlin.reflect.typeOf<Pair<T, *>>()
