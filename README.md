# onec-framework

Reusable Spring Boot starters for building oneC-style business applications in Java.

The repository is a Gradle multi-module build. Applications usually consume one or more published artifacts rather than including this repository as a composite build.

For teams or AI agents using these libraries to build an ERP application, start with [Building ERPs With onec-framework And AI Agents](BUILDING_ERPS_WITH_AGENTS.md).

## Modules

| Module | Purpose |
| --- | --- |
| `onec-framework` | Core annotations, metadata scanners, repository contracts, schema generation, posting, UI layout model, and shared types. |
| `onec-framework-starter` | Spring Boot auto-configuration for the core framework and repositories. |
| `onec-ui-starter` | Generic web UI controllers and packaged frontend assets. |
| `onec-auth-starter` | Basic Spring Security auto-configuration and auth API endpoints. |
| `onec-print-starter` | Thymeleaf-based document rendering and PDF output support. |
| `onec-mail-starter` | Mail templates, dispatchers, suppression, preview endpoints, and outbox relay support. |
| `onec-kafka-starter` | Kafka event publishing, inbox routing, service registry, and remote reference helpers. |
| `onec-desktop-starter` | Desktop runtime support and packaged Tauri shell resources. |
| `onec-desktop-gradle-plugin` | Gradle plugin for packaging a Spring Boot app as a native desktop bundle. |
| `onec-hospedajes-starter` | Spanish SES.HOSPEDAJES lodging/traveler registration client. |
| `onec-guesty-starter` | Guesty Open API client and token management. |
| `example` | Local example application. It is not intended to be published as a library. |

## Requirements

- Java 21
- Gradle wrapper from this repository
- Spring Boot 3.4.x in consuming applications
- Node 20 is downloaded automatically when building `onec-ui-starter`

## Local Development

Build and test all modules:

```bash
./gradlew clean check
```

Publish artifacts to the local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then consume them from another local project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.onec:onec-framework-starter:0.1.0")
    implementation("com.onec:onec-ui-starter:0.1.0")
}
```

## GitHub Packages

The library modules are configured to publish to GitHub Packages at:

```text
https://maven.pkg.github.com/onec-erp/onec-framework
```

Publish with:

```bash
GITHUB_ACTOR=your-user GITHUB_TOKEN=your-token ./gradlew publish
```

CI publishes packages from version tags. Push a tag named `v0.1.0` to publish artifacts with version `0.1.0`:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Release candidates work the same way. A tag named `v0.1.0-rc1` publishes artifacts with version `0.1.0-rc1`:

```bash
git tag v0.1.0-rc1
git push origin v0.1.0-rc1
```

The release workflow also supports manual dispatch with an explicit version. In both cases, CI runs `clean check` before publishing.
After publishing packages, the workflow creates a GitHub Release with generated notes. Versions with a suffix, such as `0.1.0-rc1`, are marked as pre-releases.

A consuming project can resolve published artifacts with:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/onec-erp/onec-framework")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.onec:onec-framework-starter:0.1.0")
}
```

Store credentials outside source control, for example in `~/.gradle/gradle.properties`:

```properties
gpr.user=your-user
gpr.key=your-token
```

## Desktop Plugin

Inside this repository, `settings.gradle.kts` uses `includeBuild("onec-desktop-gradle-plugin")`, so the example app can apply:

```kotlin
plugins {
    id("com.onec.desktop")
}
```

External projects should either resolve the plugin from the published package repository or include the plugin build locally during development:

```kotlin
pluginManagement {
    includeBuild("../onec-framework/onec-desktop-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Spring Boot Auto-Configuration

Each starter exposes its auto-configuration through Spring Boot 3's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` mechanism. In a consuming app, adding the starter dependency is enough to make its conditional beans available.

Most integration starters are disabled by default and are enabled through `onec.*` configuration properties. See the module READMEs for integration-specific setup.
