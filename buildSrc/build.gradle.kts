 plugins {
     val kotlinVersion = "2.2.0"
     kotlin("jvm") version kotlinVersion
     kotlin("plugin.serialization") version kotlinVersion
}

 repositories {
     mavenCentral()
 }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
