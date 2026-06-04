plugins {
    java
    id("org.springframework.boot") version "3.4.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// The version a build publishes. Release pipelines pass an explicit version via the
// `releaseVersion` Gradle property or the `RELEASE_VERSION` env var. When neither is set
// (the common case for a local `publishToMavenLocal`), default to a `-SNAPSHOT` version so a
// local build can never collide with — and silently shadow — a released tag in mavenLocal.
// See issue #31: a bare `0.1.0` default meant a HEAD build published to mavenLocal masked the
// genuinely older released `0.1.0`, breaking consumers that later switched the same coordinate
// back to the registry.
val releaseVersion = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orElse("0.1.0-SNAPSHOT")

allprojects {
    group = "com.onec"
    version = releaseVersion.get()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
