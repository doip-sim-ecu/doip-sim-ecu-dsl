plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    `maven-publish`
    `java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib"))

    api("com.github.doip-sim-ecu:doip-simulation:1.4.5")
    api("com.github.doip-sim-ecu:doip-library:1.1.8")
    api("com.github.doip-sim-ecu:doip-logging:1.1.9")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
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

allOpen {
    annotation("helper.Open")
}

