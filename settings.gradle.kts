pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm").version(kotlinVersion).apply(false)
        id("com.github.johnrengelman.shadow") version "7.1.0" apply false
        kotlin("plugin.allopen").version(kotlinVersion).apply(false)
    }
}

rootProject.name = "doip-sim-ecu-dsl"
include("doip-sim-ecu-dsl")
include("doip-sim-ecu-dsl-example")
//include("doip-sim-ecu-dsl-test")
