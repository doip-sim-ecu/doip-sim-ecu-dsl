 plugins {
     val kotlinVersion = "1.9.23"
     kotlin("jvm") version kotlinVersion
     kotlin("plugin.serialization") version kotlinVersion
}

 repositories {
     mavenCentral()
 }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
