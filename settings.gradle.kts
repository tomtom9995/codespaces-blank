rootProject.name = "nova"

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

// Mobile-Build: gemeinsames KMP-Modul + Android-App. Das Backend ist ein eigener Build in backend/.
include(":shared")
include(":apps:android")
