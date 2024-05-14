package com.magicreg.nalaq

fun main(args: Array<String>) {
    val config = loadConfiguration(args)
    if (config != null)
        runConfiguration(config)
    else if (args.isNotEmpty())
        runConfiguration(defaultConfiguration(args.joinToString(" ")))
    else
        runConfiguration(defaultConfiguration())
}
