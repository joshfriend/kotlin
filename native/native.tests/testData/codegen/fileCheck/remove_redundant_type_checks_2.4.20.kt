// TARGET_BACKEND: NATIVE
// FREE_COMPILER_ARGS: -Xbinary=genericSafeCasts=true
// FILECHECK_STAGE: CStubs
// IGNORE_KLIB_RUNTIME_ERRORS_WITH_CUSTOM_SECOND_STAGE: Native:2.4
// ^^^KT-71000: new testcases added for optimizations introduced in 2.4.20-Beta1

class A(val s: String, val x: Int, val y: Int) {
    fun sum(z: Int) = x + y + z
}

class B(val o: Any) {
    var a: A? = null
}

class C(val o: Any?)

// CHECK-LABEL: define i32 @"kfun:#test46(kotlin.Any){}kotlin.Int
fun test46(o: Any): Int {
    val x = run {
// CHECK-DEBUG: {{call|call zeroext}} i1 @IsSubtype
// CHECK-OPT: {{call|call zeroext}} i1 @IsSubclassFast
// CHECK-DEBUG: call i32 @"kfun:A#<get-x>(){}kotlin.Int
// CHECK-OPT: getelementptr inbounds nuw %"kclassbody:A#internal
        if (o is A && o.x > 0)
            return@run 0
        return@run 42
    }
// CHECK-DEBUG: {{call|call zeroext}} i1 @IsSubtype
// CHECK-OPT: {{call|call zeroext}} i1 @IsSubclassFast
// CHECK-DEBUG: call i32 @"kfun:A#<get-x>(){}kotlin.Int
// CHECK-OPT: getelementptr inbounds nuw %"kclassbody:A#internal
    return x + ((o as? A)?.x ?: -1)
// CHECK-LABEL: epilogue:
}

// CHECK-LABEL: define i32 @"kfun:#test46x(kotlin.Any){}kotlin.Int
fun test46x(o: Any): Int {
    val x = run {
// CHECK-DEBUG: {{call|call zeroext}} i1 @IsSubtype
// CHECK-OPT: {{call|call zeroext}} i1 @IsSubclassFast
// CHECK-DEBUG: call i32 @"kfun:A#<get-x>(){}kotlin.Int
// CHECK-OPT: getelementptr inbounds nuw %"kclassbody:A#internal
        if (o is A && o.x > 0)
            return@run 0
        else { }
        return@run 42
    }
// CHECK-DEBUG: {{call|call zeroext}} i1 @IsSubtype
// CHECK-OPT: {{call|call zeroext}} i1 @IsSubclassFast
// CHECK-DEBUG: call i32 @"kfun:A#<get-x>(){}kotlin.Int
// CHECK-OPT: getelementptr inbounds nuw %"kclassbody:A#internal
    return x + ((o as? A)?.x ?: -1)
// CHECK-LABEL: epilogue:
}

// CHECK-LABEL: define ptr @"kfun:#box(){}kotlin.String"
fun box(): String {
    println(test46("zzz"))
    println(test46x("zzz"))
    return "OK"
}
