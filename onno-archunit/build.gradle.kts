plugins {
    `java-library`
}

// Ready-made ArchUnit rules for applications built on onno, also used by the framework modules'
// own architecture tests. The rules name onno types by their fully-qualified names, so this module
// has no onno dependency: an application adds it as a test dependency next to its starters.
dependencies {
    api(libs.archunit)

    // The rule tests compile small fixtures against the real onno and Spring types.
    testImplementation(project(":onno-framework"))
    testImplementation(libs.spring.context)
    testImplementation(libs.spring.tx)
    testImplementation(libs.spring.web)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
