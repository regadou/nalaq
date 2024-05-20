package com.magicreg.nalaq

const val envConfigName = "NALAQ_CONFIG"
fun main(args: Array<String>) {
    val config = loadConfiguration(args)
    if (config != null)
        runConfiguration(config)
    else if (args.isNotEmpty())
        runConfiguration(defaultConfiguration(args.joinToString(" ")))
    else {
        val envConfigValue = System.getenv(envConfigName)
        if (envConfigValue.isNullOrBlank())
            runConfiguration(defaultConfiguration())
        else
            runConfiguration(loadConfiguration(arrayOf("config", envConfigValue)) ?: defaultConfiguration())
    }
}
