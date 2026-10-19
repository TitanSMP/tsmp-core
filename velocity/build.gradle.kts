plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
}
