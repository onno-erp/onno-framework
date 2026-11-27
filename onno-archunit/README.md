# onno-archunit

Ready-made [ArchUnit](https://www.archunit.org) rules for applications built on onno. Each rule turns
a "never do X" from the onno guides into a failing test whose message says what to do instead, so a
mistake — yours or a coding agent's — is caught by `./gradlew test`, not in review.

```kotlin
dependencies {
    testImplementation("su.onno:onno-archunit:<version>")
}
```

```java
class OnnoArchitectureTest {
    private static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.acme");

    @Test
    void followsOnnoRules() {
        OnnoRules.all().check(APP);
    }
}
```

With `com.tngtech.archunit:archunit-junit5` on the test classpath the same rules work as
`@ArchTest static final ArchRule onno = OnnoRules.all();` on an `@AnalyzeClasses` class.

## Rules

`OnnoRules.all()` checks:

| Rule | Fails when | Do instead |
| --- | --- | --- |
| `POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS` | a `@Transactional` method (or a method of a `@Transactional` class) calls `post`/`repost`/`unpost` on `PostingService`, `PostingEngine` or `DocumentCommandService` | save in a transaction, let it commit, then post from a non-transactional method (`propagation = NOT_SUPPORTED`/`NEVER` passes) |
| `CONTROLLERS_USE_SCOPED_SERVICES` | a `@Controller`/`@RestController` depends on a typed onno repository (`CatalogRepository`, `DocumentRepository`, `RegisterRepository`, `InformationRegisterRepository`) or on `PostingService`/`PostingEngine` | call the `su.onno.service` query/command services with the request's `AccessSubject`, a `Commands<E>` bean, or a service that checks `RecordAccess.require(...)` first |
| `DOMAIN_MODEL_IS_FRAMEWORK_NEUTRAL` | a catalog, document, tabular row, register, constant or enumeration depends on Spring beans/context/web/JDBC/transactions/security, servlets, or `su.onno.web`/`service`/`spring`/`mcp` | `Commands<E>`/`Checks<E>` beans or a `DocumentPostedEvent` listener for behaviour; the React app for presentation |
| `RECORDS_REFERENCE_RECORDS_BY_REF` | a domain class field holds a `CatalogObject`/`DocumentObject` (directly, in a collection, map, optional or array) | `Ref<T>`, or `PolyRef` with `@RefTargets` |
| `FULL_LISTS_DRAIN_KEYSET_WINDOWS` | a method returning `List` reads a `KeysetWindow.Page`'s `rows()` without draining or following `nextCursor` | `KeysetWindow.drain(c -> page(..., c, KeysetWindow.MAX_LIMIT))`, or return the `Page` |

Opt-in, not part of `all()`:

| Rule | Fails when |
| --- | --- |
| `COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS` | an HTTP `GET` handler returns a bare `Iterable`, `Stream` or array (also inside `ResponseEntity`) instead of a `KeysetWindow.Page` / `KeysetWindow.envelope(page)` |

The rules name onno types by their class names, so this module adds only ArchUnit to your test
classpath. A rule whose subject does not occur (an app with no controllers) passes.

The framework's `RecordScopeArchitectureTest` also audits direct entity-table readers. Its
`CatalogHierarchyValidator` entry is a trusted save-time integrity check over the complete parent
chain, including repository saves; it returns no record data. Caller-facing catalog commands and
validation previews check a supplied parent's read scope first, with identical preview errors for
unknown and inaccessible parents (`RecordPolicyConformanceTest`).

## Accepting existing violations

To adopt a rule in an app that already breaks it, freeze the current violations and fail only on
new ones:

```java
FreezingArchRule.freeze(OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES).check(APP);
```

with `src/test/resources/archunit.properties`:

```properties
freeze.store.default.path=src/test/resources/archunit_store
freeze.store.default.allowStoreCreation=true
```

Commit the store. A frozen violation that gets fixed drops out of it on the next run.
