pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsingbox AAR is not on Maven Central — add a local flatDir repo
        // pointing at wherever you drop the .aar file (see README.md).
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "MyVPN"
include(":app")
