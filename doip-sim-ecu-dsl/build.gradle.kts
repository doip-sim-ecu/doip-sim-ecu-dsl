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

    api("com.github.doip:doip-simulation:1.4.3")
    api("com.github.doip:doip-library:1.1.5")
    api("com.github.doip:doip-logging:1.1.7")

    api("org.apache.logging.log4j:log4j-api:2.17.0") // Enforce secure log4j version
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

