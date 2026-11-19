plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin on the JVM: no Android classes, no Hilt, no UI. Providers talk through the
// HttpGateway interface of :core:engine, so every test runs against a fake and never opens a socket.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(21) }

dependencies {
    // :core:engine re-exports :core:model and coroutines; it also owns HttpGateway, StepExecutor
    // and CredentialProvider, which the provider adapters and the Step-as-tool wrapper need.
    api(project(":core:engine"))

    testImplementation(libs.junit)
}

tasks.withType<Test> {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
