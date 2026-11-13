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
        // sherpa-onnx (on-device speech synthesis) is only published there.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.k2-fsa.*") } }
    }
}

rootProject.name = "Graveyard"
include(":app")
