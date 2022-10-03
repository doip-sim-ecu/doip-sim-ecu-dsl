plugins {
    val kotlinVersion = "1.7.10"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.allopen") version kotlinVersion
//    id("com.github.jk1.dependency-license-report") version "2.1"
    id("net.researchgate.release") version "3.0.2"
    signing
    `maven-publish`
    `java-library`
}

apply<NexusReleasePlugin>()

group = "io.github.doip-sim-ecu"
version = "0.9.8"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

val ktorVersion = "2.1.2"

dependencies {
    // Apache-2.0
    api("io.ktor:ktor-network-jvm:$ktorVersion")
    implementation(kotlin("stdlib-jdk8")) // Apache-2.0

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")

    api("ch.qos.logback:logback-classic:1.2.11") // EPL-1.0

    implementation("org.apache.commons:commons-collections4:4.4")

    implementation("io.github.hakky54:sslcontext-kickstart-for-pem:7.4.7") // Apache-2.0
    implementation("org.bouncycastle:bctls-jdk15on:1.70") // Bouncy Castle Licence (~MIT)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("DoIP Simulation ECU DSL")
                description.set("This is a a kotlin based domain specific language (dsl) to quickly and intuitively write custom DoIP ECU simulations.")
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
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

//licenseReport {
//    outputDir = "$projectDir/build/licenses"
//    projects = arrayOf(project, *project.subprojects.toTypedArray())
//    configurations = arrayOf("runtimeClasspath")
//    renderers = arrayOf(com.github.jk1.license.render.JsonReportRenderer("licenses.json", true))
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
}
