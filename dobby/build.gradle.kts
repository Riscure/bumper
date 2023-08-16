plugins {
    kotlin("jvm") version "1.8.22" apply false
    kotlin("plugin.serialization") version "1.8.22" apply false
    id("org.jetbrains.dokka") version "1.8.20"

    // for packaging build scripts writting in the kotlin dsl
    // such as our java conventions plugin.
    `kotlin-dsl`
}

repositories {
    maven {
        url = uri("http://nexus3.riscure.com:8081/repository/gradle-central-plugins/")
        isAllowInsecureProtocol = true
    }
}

