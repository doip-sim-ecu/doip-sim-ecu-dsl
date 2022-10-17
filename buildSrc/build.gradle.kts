 plugins {
     val kotlinVersion = "1.7.20"
     kotlin("jvm") version kotlinVersion
     kotlin("plugin.serialization") version kotlinVersion
}

 repositories {
     mavenCentral()
 }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
}
