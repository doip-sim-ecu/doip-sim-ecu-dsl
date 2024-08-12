 plugins {
     val kotlinVersion = "2.0.10"
     kotlin("jvm") version kotlinVersion
     kotlin("plugin.serialization") version kotlinVersion
}

 repositories {
     mavenCentral()
 }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}
