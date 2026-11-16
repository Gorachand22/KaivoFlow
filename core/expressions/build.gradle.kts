plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin on the JVM: no Android classes, no native library, no reflection.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:engine"))

    testImplementation(libs.junit)
}

tasks.withType<Test> {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
