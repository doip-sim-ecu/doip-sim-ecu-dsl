rootProject.name = "doip-sim-ecu-dsl"

//include("doip-sim-ecu-dsl-test")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("kotlinVersion", "2.2.10")
            version("cyclonedx.bom", "1.8.2")
            version("jreleaser", "1.19.0")

            library("ktor-network-jvm", "io.ktor:ktor-network-jvm:3.3.0")
            library("kotlinx-coroutines-slf4j", "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.10.2")
            library("slf4j-api", "org.slf4j:slf4j-api:2.0.17")
            library("ayza-pem", "io.github.hakky54:ayza-for-pem:10.0.0")
            library("bctls-jdk18", "org.bouncycastle:bctls-jdk18on:1.82")

            library("logback-classic", "ch.qos.logback:logback-classic:1.5.18")
            library("junit-jupiter", "org.junit.jupiter:junit-jupiter:5.13.4")
            library("mockito-kotlin", "org.mockito.kotlin:mockito-kotlin:6.0.0")
            library("assertk-jvm", "com.willowtreeapps.assertk:assertk-jvm:0.28.1")
        }
    }
}
