plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Deliberately the only dependency: :core:model stays Android-free and dependency-light
    // so the engine can be unit-tested on the JVM without Robolectric.
    api(libs.kotlinx.collections.immutable)
    testImplementation(libs.junit)
}

tasks.withType<Test> { useJUnit() }
