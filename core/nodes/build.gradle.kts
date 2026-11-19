plugins {
    alias(libs.plugins.kotlin.jvm)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(21) }

// :core:nodes is a plain JVM module on purpose: every Step executor is unit-testable with a
// fake HttpGateway, and Android capabilities arrive through the DeviceGateway interface.
dependencies {
    api(project(":core:engine"))
    api(project(":core:ai"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> { useJUnit() }
