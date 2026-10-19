plugins {
    `java-library`
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

tasks.test { useJUnitPlatform() }
