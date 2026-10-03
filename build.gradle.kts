// Kotlin 2.0.x is required: the Rokid client-m SDK pulls in kotlin-stdlib 2.1.0,
// which a 1.9.x compiler refuses to read.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}
