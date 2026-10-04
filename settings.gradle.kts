pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://chaquo.com/maven")
        maven("/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://chaquo.com/maven")
        maven("/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository")
    }
}

rootProject.name = "Spidy Assist"
include(":app")
