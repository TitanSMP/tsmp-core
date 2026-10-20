plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":modules:data-bridge"))
    implementation(project(":modules:moderation"))
    implementation(project(":modules:display"))
    runtimeOnly(project(":modules:ender-chest")) // Includes the already-applied Flyway V2 migration in the proxy classpath.
    runtimeOnly(project(":modules:economy")) // Includes the already-applied Flyway V6 economy migration in the proxy classpath.
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
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
