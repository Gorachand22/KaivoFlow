plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kaivoflow.core.data"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(project(":core:model"))
    api(project(":core:engine"))
    implementation(project(":core:ai"))
    implementation(project(":core:nodes"))

    // api: KaivoDatabase extends RoomDatabase and core:work reads KaivoDatabase.NAME, so consumers need it on their compile classpath.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}

tasks.withType<Test> { useJUnit() }
