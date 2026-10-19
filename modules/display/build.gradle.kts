plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    compileOnly("net.luckperms:api:5.5")
    // LuckPerms getters are annotated with checkerframework @Nullable; the annotation must be readable at compile time.
    compileOnly("org.checkerframework:checker-qual:4.3.1")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

tasks.test { useJUnitPlatform() }
