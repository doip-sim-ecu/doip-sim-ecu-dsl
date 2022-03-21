plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
//    id("com.github.jk1.dependency-license-report") version "2.1"
    `maven-publish`
    `java-library`
}

group = "com.github.doip-sim-ecu"
version = "0.8.0"

repositories {
    mavenCentral()
}

val ktorVersion = "1.6.7"

dependencies {
    implementation(kotlin("stdlib-jdk8")) // Apache-2.0

    api("io.ktor:ktor-network:$ktorVersion") // Apache-2.0

    api("ch.qos.logback:logback-classic:1.2.11") // EPL-1.0

    implementation("org.apache.commons:commons-collections4:4.4")

    implementation("io.github.hakky54:sslcontext-kickstart:7.2.1") // Apache-2.0
    implementation("io.github.hakky54:sslcontext-kickstart-for-pem:7.2.1") // Apache-2.0
    implementation("org.bouncycastle:bctls-jdk15on:1.70") // Bouncy Castle Licence (~MIT)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
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

