// !DIAGNOSTICS: -UNUSED_PARAMETER -UNUSED_VARIABLE

interface A
fun <T: A, R: T> emptyStrangeMap(): Map<T, R> = TODO()
fun test7() : Map<A, A> = emptyStrangeMap()

fun test() = <!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER, TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>emptyStrangeMap<!>()
