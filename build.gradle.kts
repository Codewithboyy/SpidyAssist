buildscript {
    configurations.all {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
}

plugins {
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}

allprojects {
    configurations.all {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}