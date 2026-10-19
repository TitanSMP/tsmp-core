pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "titansmp"

include("api", "core", "paper", "velocity")

include(
    "modules:data-bridge",
    "modules:ender-chest",
    "modules:moderation",
    "modules:chat",
    "modules:display",
    "modules:economy",
    "modules:social",
    "modules:stats",
    "modules:auction-house",
    "modules:admin-shop",
    "modules:tab",
    "modules:scoreboard",
    "modules:discord",
    "services:web-api",
    "services:discord-bot",
)
