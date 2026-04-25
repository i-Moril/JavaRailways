plugins {
    id("java")
    kotlin("jvm")
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    compileOnly(project(":Core"))
    compileOnly(project(":ConfigLib"))
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    implementation(kotlin("stdlib-jdk8"))
    implementation("net.jcip:jcip-annotations:1.0")
    implementation("org.joml:joml:1.10.8")
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
tasks.withType(JavaCompile::class).configureEach {
    options.release.set(17)
}