plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":Core"))
    compileOnly(project(":Basic"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":Core"))
    testImplementation(project(":Basic"))
    testImplementation(testFixtures(project(":Core")))
    testImplementation(testFixtures(project(":Basic")))
    testImplementation(project(":ConfigLib"))
    testImplementation("org.joml:joml:1.10.8")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
