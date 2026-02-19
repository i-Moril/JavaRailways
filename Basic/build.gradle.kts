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
    implementation(kotlin("stdlib-jdk8"))
    implementation("net.jcip:jcip-annotations:1.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}