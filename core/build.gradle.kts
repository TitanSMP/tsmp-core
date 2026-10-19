plugins {
    `java-library`
}

dependencies {
    api(project(":api"))

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    implementation("org.flywaydb:flyway-core:13.10.2")
    implementation("org.flywaydb:flyway-mysql:13.10.2")
    implementation("io.lettuce:lettuce-core:7.8.0.RELEASE")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("migrateDatabase") {
    group = "database"
    description = "Apply pending Flyway migrations using config/titansmp.properties and TITANSMP_* environment variables."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("de.titansmp.core.database.DatabaseMigrationMainKt")
}
