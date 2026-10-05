plugins {
    base
    id("com.diffplug.spotless") version "8.10.3"
    id("com.github.spotbugs") version "6.5.12" apply false
}

repositories {
    mavenCentral()
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "paper-plugin/*.gradle.kts")
        ktlint("1.8.0")
    }
}

tasks.named("build") {
    dependsOn(":paper-plugin:build")
}

tasks.named("check") {
    dependsOn(":paper-plugin:check")
}
