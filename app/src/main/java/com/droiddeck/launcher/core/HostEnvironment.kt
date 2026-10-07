package com.droiddeck.launcher.core

/** The variables a host process is started with, in the order they were set. */
class HostEnvironment {
    private val entries = LinkedHashMap<String, String>()

    operator fun set(name: String, value: String) {
        entries[name] = value
    }

    /** `NAME=VALUE` lines, the shape ProcessBuilder and proot take. */
    fun asArray(): Array<String> = entries.map { (name, value) -> "$name=$value" }.toTypedArray()
}
