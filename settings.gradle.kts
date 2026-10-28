rootProject.name = "uber-edge-logistics"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared:domain")
include(":shared:data")
include(":shared:presentation")
