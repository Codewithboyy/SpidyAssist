pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        
        // Fixed: Explicitly wrapped the String route string inside uri() 
        // to comply with Gradle 8.x+ strict plugin compilation specifications
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Spidy Assist"

include(":app")
