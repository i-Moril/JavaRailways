plugins {
    id("java")
    id("java-test-fixtures")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
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
    testCompileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")
    testImplementation(project(":Core"))
    testImplementation(testFixtures(project(":Core")))
    testImplementation(project(":ConfigLib"))
    testFixturesImplementation(project(":Core"))
    testFixturesImplementation(project(":ConfigLib"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("net.jcip:jcip-annotations:1.0")
    compileOnly("org.joml:joml:1.10.8")
    testImplementation("org.joml:joml:1.10.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
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

tasks.register<Jar>("dokkaJavadocJar") {
    description = "Packages the Basic API Dokka Javadoc output."
    dependsOn("dokkaGeneratePublicationJavadoc")
    from(layout.buildDirectory.dir("dokka/javadoc"))
    archiveClassifier.set("javadoc")
}

dokka {
    dokkaPublications.html {
        moduleName.set("MineRails Basic API")
        includes.from("src/main/dokka/Module.md", "src/main/dokka/BasicPackages.md")
        failOnWarning.set(false)
    }
    dokkaSourceSets.main {
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
        perPackageOption {
            matchingRegex.set("""work\.art1st\.mineRails\.basic(?!\.api(?:\.|$)).*""")
            suppress.set(true)
        }
        providers.environmentVariable("DOKKA_SOURCE_URL").orNull?.let { sourceRoot ->
            sourceLink {
                localDirectory.set(file("src/main/java"))
                remoteUrl.set(uri("${sourceRoot.trimEnd('/')}/${project.name}/src/main/java"))
                remoteLineSuffix.set("#L")
            }
        }
    }
}
