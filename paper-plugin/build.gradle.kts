import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val projectVersion = providers.gradleProperty("projectVersion").get()
val paperVersion = providers.gradleProperty("paperVersion").get()
val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val devServerHost = providers.environmentVariable("DIRT_MCP_DEV_HOST").orElse("0.0.0.0")
val devServerPort = providers.environmentVariable("DIRT_MCP_DEV_PORT").orElse("25566")

group = "ca.deliyannides.dirtmcp"
version = projectVersion
description = "The Minecraft-facing bridge for Dirt MCP"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    processResources {
        val properties = mapOf(
            "version" to project.version,
            "paperVersion" to paperVersion,
        )
        inputs.properties(properties)
        filteringCharset = "UTF-8"

        filesMatching("plugin.yml") {
            expand(properties)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName = "dirt-mcp-paper"
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    runServer {
        minecraftVersion(paperVersion)
        args("--host", devServerHost.get(), "--port", devServerPort.get())
        jvmArgs("-Djava.net.preferIPv4Stack=true")

        if (providers.environmentVariable("PAPER_EULA").map(String::toBoolean).orElse(false).get()) {
            jvmArgs("-Dcom.mojang.eula.agree=true")
        }
    }
}
