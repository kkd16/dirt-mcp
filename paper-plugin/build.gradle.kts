import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipFile

@DisableCachingByDefault(because = "Verification produces no output")
abstract class VerifyPluginJar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val archive = archiveFile.get().asFile
        val entries =
            ZipFile(archive).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toSet()
            }
        val requiredEntries =
            setOf(
                "META-INF/LICENSE",
                "plugin.yml",
                "config.yml",
                "ca/deliyannides/dirtmcp/paper/DirtMcpPlugin.class",
            )
        val missingEntries = requiredEntries - entries
        if (missingEntries.isNotEmpty()) {
            throw GradleException(
                "Built Paper JAR is missing required entries: ${missingEntries.sorted().joinToString()}",
            )
        }

        if (
            entries.any {
                it.startsWith("com/sk89q/") ||
                    it.startsWith("com/fastasyncworldedit/") ||
                    it.startsWith("org/bukkit/") ||
                    it.startsWith("io/papermc/")
            }
        ) {
            throw GradleException(
                "Built Paper JAR must not bundle Paper, Bukkit, WorldEdit, or FAWE classes",
            )
        }
        if (entries.any { it.startsWith(".dev/") }) {
            throw GradleException("Built Paper JAR contains development runtime state or credentials")
        }

        logger.lifecycle("Paper JAR validation passed: {}", archive)
    }
}

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
val paperBuild = providers.gradleProperty("paperBuild").get().toInt()
val paperApiVersion = "$paperVersion.build.$paperBuild-stable"
val faweMavenVersion = providers.gradleProperty("faweMavenVersion").get()
val faweModrinthVersionId = providers.gradleProperty("faweModrinthVersionId").get()

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
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweMavenVersion") {
        isTransitive = false
    }
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweMavenVersion") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit6:1.5.0")
    testImplementation("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweMavenVersion") {
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
}

jacoco {
    toolVersion = "0.8.15"
}

tasks {
    val verifyPluginJar =
        register<VerifyPluginJar>("verifyPluginJar") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Verify the contents of the distributable Paper plugin JAR."
            archiveFile.set(named<Jar>("jar").flatMap { it.archiveFile })
        }

    val stageRunServer =
        register<Copy>("stageRunServer") {
            group = "run paper"
            description = "Stage managed development configuration into the Paper run directory."
            into(rootProject.layout.projectDirectory.dir(".dev/paper"))
            from("src/run")
            from("src/main/resources/config.yml") {
                into("plugins/DirtMCP")
            }
        }

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
                    minimum = "0.60".toBigDecimal()
                }
            }
        }
    }

    check {
        dependsOn(jacocoTestCoverageVerification, verifyPluginJar)
    }

    assemble {
        dependsOn(verifyPluginJar)
    }

    jar {
        archiveBaseName = "dirt-mcp-paper"
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
    }

    runServer {
        dependsOn(stageRunServer, verifyPluginJar)
        runDirectory(
            rootProject.layout.projectDirectory
                .dir(".dev/paper")
                .asFile,
        )
        minecraftVersion(paperVersion)
        build(paperBuild)
        downloadPlugins {
            modrinth("z4HZZnLr", faweModrinthVersionId)
        }
        args("--host", "0.0.0.0", "--port", "25565")
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
}
