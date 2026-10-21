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

rootProject.name = "NTPX-AI-Alchemist"

include(":app")
include(":core:model-registry")
include(":core:agent-runtime")
include(":core:mcp")
include(":core:memory")
