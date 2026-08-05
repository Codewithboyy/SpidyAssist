plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Fixed: Resolved lazy provider tracking to prevent build-time clean errors
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory.get().asFile)
}