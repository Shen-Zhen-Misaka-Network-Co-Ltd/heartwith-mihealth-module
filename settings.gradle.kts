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
include(":heartwith-mihealth-lsp")
project(":heartwith-mihealth-lsp").projectDir = file("clients/heartwith-mihealth-lsp")
include(":xposed-api-stub")
project(":xposed-api-stub").projectDir = file("clients/xposed-api-stub")
