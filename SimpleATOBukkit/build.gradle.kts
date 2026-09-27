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
    implementation(kotlin("stdlib-jdk8"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":Core"))
    testImplementation(project(":Basic"))
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
