package com.droiddeck.launcher.runtime

/**
 * A child process for [GuestOutputTest]: one that says something and exits, and one that says
 * nothing at all and never exits - the shape a wedged proot child has. Run as a real second JVM,
 * because that is the only kind of child every machine this project is built on has.
 */
object GuestOutputTestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "print" -> {
                println("first line")
                println("second line")
            }
            // The stuck one: alive, silent, and holding its output stream open.
            else -> Thread.sleep(120_000)
        }
    }
}
