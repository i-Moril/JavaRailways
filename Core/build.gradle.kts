plugins {
    id("java")
    id("java-test-fixtures")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
    //id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ConfigLib"))
    testFixturesImplementation(project(":ConfigLib"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("org.joml:joml:1.10.8")
    testImplementation("org.joml:joml:1.10.8")
    testFixturesImplementation("org.joml:joml:1.10.8")
    implementation("net.jcip:jcip-annotations:1.0")
    //implementation(project(":Annotation"))
    //ksp(project(":Annotation"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("physics-stress")
    }
}

val physicsStressProperties = listOf(
    "physicsStress.trainCounts",
    "physicsStress.workerThreads",
    "physicsStress.carCount",
    "physicsStress.warmupEpochs",
    "physicsStress.measurementEpochs",
    "physicsStress.repetitions",
    "physicsStress.budgetMillis",
    "physicsStress.scenarios",
    "physicsStress.speedMetersPerSecond",
    "physicsStress.sharedSpacingMeters",
    "physicsStress.trackShape",
    "physicsStress.requiredTrainCount"
)

tasks.register<Test>("physicsStressTest") {
    description = "Runs the opt-in Core headless physics capacity stress suite."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("physics-stress")
    }
    physicsStressProperties.forEach { propertyName ->
        project.findProperty(propertyName)?.toString()?.let { propertyValue ->
            systemProperty(propertyName, propertyValue)
        }
    }
    systemProperty(
        "physicsStress.reportDir",
        layout.buildDirectory.dir("reports/physics-stress").get().asFile.absolutePath
    )
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}
kotlin {
    jvmToolchain(17)
}

tasks.register<Jar>("dokkaJavadocJar") {
    description = "Packages the Core API Dokka Javadoc output."
    dependsOn("dokkaGeneratePublicationJavadoc")
    from(layout.buildDirectory.dir("dokka/javadoc"))
    archiveClassifier.set("javadoc")
}

dokka {
    dokkaPublications.html {
        moduleName.set("JavaRailways Core API")
        includes.from("src/main/dokka/Module.md", "src/main/dokka/CorePackages.md")
        failOnWarning.set(false)
    }
    dokkaSourceSets.main {
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
        perPackageOption {
            matchingRegex.set("""work\.art1st\.javarailways\.core(?!\.api(?:\.|$)).*""")
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
