plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kaivoflow.core.work"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:engine"))
    api(project(":core:model"))
    implementation(project(":core:nodes"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:expressions"))

    implementation(libs.androidx.work.runtime.ktx)
    // GeofencingClient (AO-13). Absent or unusable Play services fall back to proximity alerts at runtime.
    implementation(libs.play.services.location)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
}
