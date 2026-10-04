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
    }
}

rootProject.name = "LovelySpace"

include(":core")
// LS_JVM_ONLY=1 builds only the pure-Kotlin modules (for environments without the Android SDK / Google Maven).
if (providers.environmentVariable("LS_JVM_ONLY").orNull.isNullOrEmpty()) {
    include(":app")
}
