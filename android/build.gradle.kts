plugins {
    // AGP 9 provides built-in Kotlin support, so no separate Kotlin plugin is
    // applied here. The Compose compiler plugin is still required.
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
