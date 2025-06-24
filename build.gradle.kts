import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version libs.versions.kotlinVersion
    kotlin("plugin.allopen") version libs.versions.kotlinVersion
//    id("com.github.jk1.dependency-license-report") version "2.1"
    id("org.cyclonedx.bom") version libs.versions.cyclonedx.bom
    id("net.researchgate.release") version libs.versions.researchgate.release
    signing
    `maven-publish`
    `java-library`
}

apply<NexusReleasePlugin>()

group = "io.github.doip-sim-ecu"
version = "0.22.0-SNAPSHOT"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8")) // Apache-2.0
    api(libs.ktor.network.jvm) // Apache-2.0
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")  // Apache-2.0
    implementation(libs.slf4j.api) // MIT

    implementation(libs.sslcontext.kickstart.pem) // Apache-2.0
    implementation(libs.bctls.jdk18) // Bouncy Castle License (~MIT)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.logback.classic) // EPL-1.0
    testImplementation(libs.junit.jupiter)
    // version 5.x requires jdk 11
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.assertk.jvm)
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
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
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
            signingKey // .replace(" ", "\n")
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
