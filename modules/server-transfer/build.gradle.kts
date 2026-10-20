plugins {
    `java-library`
}

dependencies {
    api(project(":api"))
    implementation(project(":core"))
    implementation("io.lettuce:lettuce-core:7.8.0.RELEASE")
    compileOnly("net.luckperms:api:5.5")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

tasks.test { useJUnitPlatform() }
