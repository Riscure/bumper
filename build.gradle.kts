plugins {
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20" apply false
}

val releases  = uri("http://nexus3.riscure.com:8081/repository/riscure")
val snapshots = uri("http://nexus3.riscure.com:8081/repository/riscure-snapshots")

repositories {
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }

    maven {
        url = releases
        isAllowInsecureProtocol = true
    }

    maven {
        url = snapshots
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

    maven {
        url = uri("http://nexus3.riscure.com:8081/repository/p2-proxy")
        isAllowInsecureProtocol = true
    }

    maven {
        url = uri("http://nexus3.riscure.com:8081/repository/npmjs-org-proxy")
        isAllowInsecureProtocol = true
    }

    mavenLocal()
}
