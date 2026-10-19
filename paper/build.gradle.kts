plugins {
    `java-library`
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
}
