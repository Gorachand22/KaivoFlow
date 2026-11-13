plugins {
    alias(libs.plugins.kotlin.jvm)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }

// :core:nodes is a plain JVM module on purpose: every Step executor is unit-testable with a
// fake HttpGateway, and Android capabilities arrive through the DeviceGateway interface.
dependencies {
    api(project(":core:engine"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> { useJUnit() }
