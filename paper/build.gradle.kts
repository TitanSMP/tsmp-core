plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":modules:ender-chest"))
    implementation(project(":modules:data-bridge"))
    implementation(project(":modules:moderation"))
    implementation(project(":modules:chat"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
    compileOnly("net.luckperms:api:5.5")

    testImplementation("io.papermc.paper:paper-api:26.3.build.205-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.118.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

tasks.test {
    useJUnitPlatform()
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
    from(project(":modules:ender-chest").the<SourceSetContainer>()["main"].output)
    from(project(":modules:data-bridge").the<SourceSetContainer>()["main"].output)
    from(project(":modules:moderation").the<SourceSetContainer>()["main"].output)
    from(project(":modules:chat").the<SourceSetContainer>()["main"].output)
}
