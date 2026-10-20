plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    runtimeOnly(project(":modules:economy"))
    runtimeOnly(project(":modules:auction-house"))
    runtimeOnly(project(":modules:ender-chest"))
    runtimeOnly(project(":modules:discord"))
    runtimeOnly(project(":modules:stats"))
    runtimeOnly(project(":modules:friends"))
    runtimeOnly(project(":modules:clans"))
    runtimeOnly(project(":modules:teleport"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    testImplementation("org.flywaydb:flyway-core:13.10.2")
    testImplementation("org.flywaydb:flyway-mysql:13.10.2")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

tasks.test { useJUnitPlatform() }
