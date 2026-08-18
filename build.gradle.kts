plugins {
    base
}

tasks.named("build") {
    dependsOn(":paper-plugin:build")
}

tasks.named("check") {
    dependsOn(":paper-plugin:check")
}
