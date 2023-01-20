pluginManagement {
    repositories {
        // We first try the nexus proxy for gradle plugins (visible from bamboo)
        maven {
            url = uri("http://nexus3.riscure.com:8081/repository/gradle-central-plugins/")
            isAllowInsecureProtocol = true
        }
        // If it fails, we default on gradle plugin portal (not visible from bamboo, requires internet)
        gradlePluginPortal()
    }

    dependencyResolutionManagement {
        repositories {
            maven {
                url = uri("http://nexus3.riscure.com:8081/repository/riscure")
                isAllowInsecureProtocol = true
            }

            maven {
                url = uri("http://nexus3.riscure.com:8081/repository/riscure-snapshots")
                isAllowInsecureProtocol = true
            }

            maven {
                url = uri("http://nexus3.riscure.com:8081/repository/maven-central/")
                isAllowInsecureProtocol = true
            }

            maven {
                url = uri("http://nexus3.riscure.com:8081/repository/3rdparty")
                isAllowInsecureProtocol = true
            }

            maven {
                url = uri("http://nexus3.riscure.com:8081/repository/3rdparty-snapshots")
                isAllowInsecureProtocol = true
            }
        }

        versionCatalogs {
            create("kotlinx") {
                library("json"                   , "org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
                library("coroutines-core"        , "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")
                library("serialization-core"     , "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.4.1")
                library("serialization-protobuf" , "org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.4.1")
            }

            create("libs") {
                library("arrow.core"     , "io.arrow-kt:arrow-core:1.1.2")
                library("apache.commons" , "org.apache.commons:commons-lang3:3.8.1")
                library("slf4j"          , "org.slf4j:slf4j-api:1.7.25")
                library("process"        , "com.github.pgreze:kotlin-process:1.4")
                library("junit"          , "org.junit.jupiter:junit-jupiter:5.8.2")
                library("dobby"          , "com.riscure:riscure-dobby:0.1.0")
            }
        }
    }
}

rootProject.name = "bumper"

include(":bumper-core")
project(":bumper-core").projectDir = file("core/")

include(":bumper-test")
project(":bumper-test").projectDir = file("test/")

include(":bumper-libclang")
project(":bumper-libclang").projectDir = file("implementations/libclang")