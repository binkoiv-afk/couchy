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
        // NewPipe Extractor (YouTube without ads) is published only there.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\..*") } }
    }
}

rootProject.name = "couchy"

include(":core")
// -PcoreOnly lets the pure-Kotlin core build and test on machines without an Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
