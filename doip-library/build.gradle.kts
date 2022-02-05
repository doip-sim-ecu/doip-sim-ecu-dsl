plugins {
    kotlin("jvm") // Standalone: Add version
    `java-library`
}

repositories {
    mavenCentral()
}

val ktorVersion = "1.6.7"

dependencies {
    implementation(kotlin("stdlib"))

//    implementation("io.ktor:ktor-server-core:$ktorVersion")
//    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-network:$ktorVersion")
//    implementation("ch.qos.logback:logback-classic:1.2.5")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
}
