plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":modules:data-bridge"))
    implementation(project(":modules:moderation"))
    implementation(project(":modules:display"))
    implementation(project(":modules:clans"))
    implementation(project(":modules:server-transfer"))
    implementation(project(":modules:teleport"))
    implementation("io.lettuce:lettuce-core:7.8.0.RELEASE")
    runtimeOnly(project(":modules:ender-chest")) // Includes the already-applied Flyway V2 migration in the proxy classpath.
    runtimeOnly(project(":modules:discord")) // Includes the Flyway V5 account-link migration in the proxy classpath.
    runtimeOnly(project(":modules:economy")) // Includes the already-applied Flyway V6 economy migration in the proxy classpath.
    runtimeOnly(project(":modules:stats")) // Includes the Flyway V7 stats migration in the proxy classpath.
    runtimeOnly(project(":modules:clans")) // Includes the Flyway V9 clan migration in the proxy classpath.
    runtimeOnly(project(":modules:teleport")) // Includes the Flyway V10 teleport migration in the proxy classpath.
    runtimeOnly(project(":modules:friends")) // Includes the Flyway V8 friends migration preceding clans.
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
