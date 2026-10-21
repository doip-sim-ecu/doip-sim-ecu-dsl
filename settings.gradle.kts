rootProject.name = "doip-sim-ecu-dsl"

//include("doip-sim-ecu-dsl-test")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("kotlinVersion", "2.4.21")
            version("cyclonedx.bom", "3.5.1")
            version("jreleaser", "1.26.0")

            library("ktor-network-jvm", "io.ktor:ktor-network-jvm:3.6.0")
            library("kotlinx-coroutines-slf4j", "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.11.0")
            // optional dependency for SocketCAN support (linux only)
            library("javacan-core", "tel.schich:javacan-core:3.5.2")
            library("slf4j-api", "org.slf4j:slf4j-api:2.0.20")
            library("ayza-pem", "io.github.hakky54:ayza-for-pem:10.1.0")
            library("bctls-jdk18", "org.bouncycastle:bctls-jdk18on:1.86.2")

            library("logback-classic", "ch.qos.logback:logback-classic:1.6.5")
            library("kotlinx-coroutines-test", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            library("junit-jupiter", "org.junit.jupiter:junit-jupiter:6.1.3")
            library("mockito-kotlin", "org.mockito.kotlin:mockito-kotlin:6.4.0")
            library("assertk-jvm", "com.willowtreeapps.assertk:assertk-jvm:0.28.1")
        }
    }
}
