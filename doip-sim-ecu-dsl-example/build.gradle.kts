plugins {
    kotlin("jvm") // Standalone: Add version
    id("com.github.johnrengelman.shadow") version "7.1.0"
    application
}

group = "com.github.froks.exampleproject"
version = "0.1"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib"))
//    implementation(project(":doip-sim-ecu-dsl"))
    // Standalone:
    implementation("com.github.froks:doip-sim-ecu-dsl:main-SNAPSHOT")
}

tasks {
    application {
        mainClass.set("MainKt")
    }
}
