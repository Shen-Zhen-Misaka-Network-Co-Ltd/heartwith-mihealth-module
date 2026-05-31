pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "heartwith-mihealth-module"

val uploaderBuild = listOf(
    file("../heartwith-android-uploader"),
    file("heartwith-android-uploader"),
).firstOrNull { it.resolve("settings.gradle.kts").isFile }

if (uploaderBuild != null) {
    includeBuild(uploaderBuild) {
        dependencySubstitution {
            substitute(module("com.heartwith:heartwith-android-uploader"))
                .using(project(":heartwith-android-uploader"))
        }
    }
}

include(":heartwith-mihealth-lsp")
project(":heartwith-mihealth-lsp").projectDir = file("clients/heartwith-mihealth-lsp")
include(":xposed-api-stub")
project(":xposed-api-stub").projectDir = file("clients/xposed-api-stub")
