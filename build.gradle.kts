// Top-level build file. Plugin versions live here; the app module applies them.
plugins {
    id("com.android.application") version "9.5.2" apply false
    // Android Gradle Plugin 9 compiles Kotlin by itself. Declaring the Kotlin
    // plugin here (without applying it) only pins which Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // Compose compiler. Its version must match the Kotlin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
