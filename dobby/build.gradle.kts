import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
    `maven-publish`

    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"

    antlr
}

group   = "com.riscure"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0")

    // third party
    implementation("io.arrow-kt:arrow-core:1.1.2")
    implementation("com.github.pgreze:kotlin-process:1.4")

    // resources
    runtimeOnly(files("./src/main/resources/clang.options.json"))

    // test deps
    testImplementation(kotlin("test"))

    // Use Antlr 4 for the parser generation
    antlr("org.antlr:antlr4:4.11.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events(PASSED, FAILED, STANDARD_OUT, STANDARD_ERROR, SKIPPED)
        exceptionFormat = FULL
    }
}

tasks.withType<KotlinCompile> {
    dependsOn(tasks.generateGrammarSource)

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xcontext-receivers")
    }
}

// ANTLR plugin configuration.
// This plugin really behaves meh.

tasks.generateGrammarSource {
    arguments   = arguments + listOf(
        "-lib", "./src/main/antlr/com/riscure/lang/shell/",
        "-no-visitor",
        "-no-listener"
    )
}

// Publishing

fun env(key: String): String? = System.getenv(key)

val nexusUsername = env("NEXUS_USERNAME")
val nexusPassword = env("NEXUS_PASSWORD")

val releases  = uri("http://nexus3.riscure.com:8081/repository/riscure")
val snapshots = uri("http://nexus3.riscure.com:8081/repository/riscure-snapshots")

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId    = "com.riscure"
            artifactId = rootProject.name
            version    = version

            from(components["java"])

            pom {
                name.set(rootProject.name)
                description.set("The friendly compilation database elf")
            }
        }
    }

    repositories {
        maven {
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases)
            isAllowInsecureProtocol = true
            credentials {
                username = nexusUsername
                password = nexusPassword
            }
        }
    }
}