plugins {
    val kotlinVersion = "2.0.0"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.allopen") version kotlinVersion
//    id("com.github.jk1.dependency-license-report") version "2.1"
    id("org.cyclonedx.bom") version "1.9.0"
    id("net.researchgate.release") version "3.0.2"
    signing
    `maven-publish`
    `java-library`
}

apply<NexusReleasePlugin>()

group = "io.github.doip-sim-ecu"
version = "0.13.0"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    implementation(kotlin("stdlib-jdk8")) // Apache-2.0
    api("io.ktor:ktor-network-jvm:$ktorVersion") // Apache-2.0
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")  // Apache-2.0
    implementation("org.slf4j:slf4j-api:2.0.13") // MIT

    implementation("io.github.hakky54:sslcontext-kickstart-for-pem:8.3.0") // Apache-2.0
    implementation("org.bouncycastle:bctls-jdk18on:1.78.1") // Bouncy Castle License (~MIT)

    testImplementation(kotlin("test"))
    testRuntimeOnly("ch.qos.logback:logback-classic:1.3.14") // EPL-1.0
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    // version 5.x requires jdk 11
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    explicitApi()
//    sourceSets.all {
//        languageSettings {
//            languageVersion = "2.0"
//        }
//    }
}

tasks.withType<JavaCompile>().configureEach {
    targetCompatibility = "1.8"
    sourceCompatibility = "1.8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("DoIP Simulation ECU DSL")
                description.set("This is a kotlin based domain specific language (dsl), to quickly and intuitively write custom DoIP ECU simulations.")
                url.set("https://github.com/doip-sim-ecu/doip-sim-ecu-dsl")
                developers {
                    developer {
                        id.set("froks")
                        name.set("Florian Roks")
                        email.set("flo.github@debugco.de")
                    }
                }
                scm {
                    url.set("https://github.com/doip-sim-ecu/doip-sim-ecu-dsl")
                    developerConnection.set("scm:git:ssh://github.com:doip-sim-ecu/doip-sim-ecu-dsl.git")
                    connection.set("scm:git:git://github.com/doip-sim-ecu/doip-sim-ecu-dsl.git")
                }
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            val publishSnapshotUrl: String? by project
            val publishReleaseUrl: String? by project
            url = uri((if (version.toString().endsWith("SNAPSHOT")) publishSnapshotUrl else publishReleaseUrl) ?: "invalid")
            credentials {
                val ossrhUsername: String? = System.getenv("OSSRH_USERNAME")
                val ossrhPassword: String? = System.getenv("OSSRH_PASSWORD")
                username = ossrhUsername
                password = ossrhPassword
            }
        }
    }
}

//tasks.cyclonedxBom {
//    setIncludeConfigs(listOf("runtimeClasspath"))
//    setIncludeLicenseText(false)
//    outputName.set("sbom")
//    outputFormat.set("all")
//    includeBomSerialNumber.set(true)
//}

allOpen {
    annotation("helper.Open")
}

signing {
    val signingKey: String? = System.getenv("SIGNING_KEY")
    val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
    if (signingKey != null) {
        val file = File(signingKey)
        val data = if (file.exists()) {
            file.readText()
        } else {
            signingKey.replace(" ", "\n")
        }
        useInMemoryPgpKeys(data, signingPassword)
        sign(publishing.publications)
    } else {
        println("Jar file isn't signed")
    }
}

configure<NexusReleaseExtension> {
    username.set(System.getenv("OSSRH_USERNAME"))
    password.set(System.getenv("OSSRH_PASSWORD"))
    stagingUserName.set(System.getenv("OSSRH_STAGING_USERNAME"))
}
