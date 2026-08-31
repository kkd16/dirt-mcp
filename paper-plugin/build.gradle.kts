import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    java
    jacoco
    pmd
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val projectVersion = providers.gradleProperty("projectVersion").get()
val paperVersion = providers.gradleProperty("paperVersion").get()
val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val faweVersion = providers.gradleProperty("faweVersion").get()
val faweModrinthVersionId = providers.gradleProperty("faweModrinthVersionId").get()
val devServerPort = providers.environmentVariable("DIRT_DEV_PORT").orElse("25566")

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
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion") {
        isTransitive = false
    }
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit6:1.5.0")
    testImplementation("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion") {
        isTransitive = false
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

spotless {
    java {
        googleJavaFormat("1.36.1").aosp()
        formatAnnotations()
    }
}

pmd {
    toolVersion = "7.27.0"
    isConsoleOutput = true
    rulesMinimumPriority = 2
    ruleSets =
        listOf(
            "category/java/errorprone.xml",
            "category/java/bestpractices.xml",
        )
}

spotbugs {
    toolVersion = "4.10.4"
    effort = Effort.MAX
    reportLevel = Confidence.HIGH
    ignoreFailures = false
}

jacoco {
    toolVersion = "0.8.15"
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    processResources {
        val properties =
            mapOf(
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

    jacocoTestCoverageVerification {
        dependsOn(test)
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.70".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = "0.58".toBigDecimal()
                }
            }
        }
    }

    check {
        dependsOn(jacocoTestCoverageVerification)
    }

    jar {
        archiveBaseName = "dirt-mcp-paper"
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    runServer {
        minecraftVersion(paperVersion)
        downloadPlugins {
            modrinth("z4HZZnLr", faweModrinthVersionId)
        }
        args("--host", "0.0.0.0", "--port", devServerPort.get())
        jvmArgs("-Djava.net.preferIPv4Stack=true")

        if (providers
                .environmentVariable("PAPER_EULA")
                .map(String::toBoolean)
                .orElse(false)
                .get()
        ) {
            jvmArgs("-Dcom.mojang.eula.agree=true")
        }
    }
}
