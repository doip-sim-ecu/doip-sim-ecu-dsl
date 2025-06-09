rootProject.name = "doip-sim-ecu-dsl"

//include("doip-sim-ecu-dsl-test")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("kotlinVersion", "2.1.20")
            version("cyclonedx.bom", "1.8.2")
            version("researchgate.release", "3.0.2")

            library("ktor-network-jvm", "io.ktor:ktor-network-jvm:3.1.3")
            library("slf4j-api", "org.slf4j:slf4j-api:2.0.17")
            library("sslcontext-kickstart-pem", "io.github.hakky54:sslcontext-kickstart-for-pem:9.1.0")
            library("bctls-jdk18", "org.bouncycastle:bctls-jdk18on:1.80")

            library("logback-classic", "ch.qos.logback:logback-classic:1.3.14")
            library("junit-jupiter", "org.junit.jupiter:junit-jupiter:5.13.1")
            library("mockito-kotlin", "org.mockito.kotlin:mockito-kotlin:4.1.0")
            library("assertk-jvm", "com.willowtreeapps.assertk:assertk-jvm:0.28.1")
        }
    }
}
