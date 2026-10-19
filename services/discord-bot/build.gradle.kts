plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":modules:discord"))
    implementation(project(":modules:chat"))
    implementation(project(":modules:display"))

    // JDA 6.7.0 - the Discord library used by this runtime. Version is documented in docs/discord.md.
    // opus-java only serves voice audio and pulls native libraries this bot never loads.
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
    }

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.2")
}

application {
    mainClass.set("de.titansmp.discord.DiscordBotMainKt")
}

tasks.test {
    useJUnitPlatform()
}
