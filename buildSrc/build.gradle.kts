 plugins {
     val kotlinVersion = "1.8.22"
     kotlin("jvm") version kotlinVersion
     kotlin("plugin.serialization") version kotlinVersion
}

 repositories {
     mavenCentral()
 }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
}
