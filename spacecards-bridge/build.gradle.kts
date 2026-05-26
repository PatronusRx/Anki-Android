// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ankidroid.android.library")
    alias(libs.plugins.kotlin.serialization)
}

configure<LibraryExtension> {
    namespace = "com.spacecardsvr.bridge"
    buildFeatures.buildConfig = false
}

dependencies {
    implementation(project(":libanki"))
    implementation(project(":anki-common"))
    implementation(project(":common"))
    implementation(project(":common:android"))
    implementation(project(":compat"))

    implementation(libs.ankiBackend.backend)
    implementation(libs.androidx.sqlite.framework)

    implementation(libs.jakewharton.timber)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)

    lintChecks(project(":lint-rules"))
}
