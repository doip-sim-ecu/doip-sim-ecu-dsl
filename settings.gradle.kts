pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm").version(kotlinVersion).apply(false)
        kotlin("plugin.allopen").version(kotlinVersion).apply(false)
    }
}

rootProject.name = "doip-sim-ecu-dsl"

include("doip-sim-ecu-dsl-test")
