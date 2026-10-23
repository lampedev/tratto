// AGP 9 compila Kotlin da solo ("built-in Kotlin"): NON applicare org.jetbrains.kotlin.android.
// AGP 9.4.1 dipende da KGP 2.2.10; qui lo alziamo alla stessa versione del plugin Compose
// mettendolo sul classpath, come indicato nelle release notes di AGP 9.0.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
