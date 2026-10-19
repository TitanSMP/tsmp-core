plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.jar {
    // Paper loads a single plugin JAR; include the project modules this entry point uses.
    from(project(":api").the<SourceSetContainer>()["main"].output)
    from(project(":core").the<SourceSetContainer>()["main"].output)
}
