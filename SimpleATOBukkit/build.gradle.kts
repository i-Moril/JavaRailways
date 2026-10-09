plugins {
    `java-library`
    kotlin("jvm")
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":SimpleATO"))
    compileOnly(project(":Core"))
    compileOnly(project(":Basic"))
    implementation(kotlin("stdlib-jdk8"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":Core"))
    testImplementation(testFixtures(project(":Core")))
    testImplementation(project(":Basic"))
    testImplementation(project(":ConfigLib"))
    testImplementation("org.joml:joml:1.10.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
