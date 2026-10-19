plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":modules:data-bridge"))
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().flatMap { dependency ->
            if (dependency.isDirectory) listOf(dependency) else zipTree(dependency).files
        }
    })
}

tasks.processResources {
    filesMatching("velocity-plugin.json") {
        expand("project" to mapOf("version" to project.version))
    }
}
