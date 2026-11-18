package su.onno.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Ready-made <a href="https://www.archunit.org">ArchUnit</a> rules for applications built on onno.
 *
 * <p>Each rule turns a "never do X" from the onno guides into a failing test whose message names
 * the fix. Check them all in one test:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.acme", importOptions = ImportOption.DoNotIncludeTests.class)
 * class OnnoArchitectureTest {
 *     @ArchTest
 *     static final ArchRule onno = OnnoRules.all();
 * }
 * }</pre>
 *
 * <p>or pick individual rules. The rules refer to onno types by name, so they work on any class
 * set; a rule whose subject (for example a {@code @Transactional} method) does not occur passes.
 * Opt-in rules that are not part of {@link #all()} are documented on their constants.
 */
public final class OnnoRules {

    // --- onno types, by name so this module needs no onno dependency ---------------------------

    static final List<String> RECORD_TYPES = List.of(
            "su.onno.model.CatalogObject",
            "su.onno.model.DocumentObject");

    static final List<String> DOMAIN_BASE_TYPES = List.of(
            "su.onno.model.CatalogObject",
            "su.onno.model.DocumentObject",
            "su.onno.model.TabularSectionRow",
            "su.onno.model.AccumulationRecord",
            "su.onno.model.InformationRecord");

    static final List<String> DOMAIN_ANNOTATIONS = List.of(
            "su.onno.annotations.Catalog",
            "su.onno.annotations.Document",
            "su.onno.annotations.AccumulationRegister",
            "su.onno.annotations.InformationRegister",
            "su.onno.annotations.Constant",
            "su.onno.annotations.Enumeration");

    /** Unscoped persistence: typed repositories ignore {@code @AccessControl} and record policies. */
    static final List<String> UNSCOPED_REPOSITORIES = List.of(
            "su.onno.repository.CatalogRepository",
            "su.onno.repository.DocumentRepository",
            "su.onno.repository.RegisterRepository",
            "su.onno.repository.InformationRegisterRepository");

    /** Trusted posting entry points: they do not check {@code postRoles}/{@code unpostRoles}. */
    static final List<String> TRUSTED_POSTING = List.of(
            "su.onno.posting.PostingService",
            "su.onno.posting.PostingEngine");

    /** Every type whose {@code post}/{@code repost}/{@code unpost} opens its own JDBI transaction. */
    static final List<String> POSTING_OWNERS = List.of(
            "su.onno.posting.PostingService",
            "su.onno.posting.PostingEngine",
            "su.onno.service.DocumentCommandService");

    static final Set<String> POSTING_METHODS = Set.of("post", "repost", "unpost");

    static final List<String> TRANSACTIONAL = List.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional",
            "javax.transaction.Transactional");

    static final String CONTROLLER = "org.springframework.stereotype.Controller";
    static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    static final String RESPONSE_ENTITY = "org.springframework.http.ResponseEntity";
    static final String KEYSET_PAGE = "su.onno.query.KeysetWindow$Page";
    static final String KEYSET_WINDOW = "su.onno.query.KeysetWindow";

    private OnnoRules() {
    }

    // --- rules ---------------------------------------------------------------------------------

    /**
     * Posting runs in its own JDBI transaction, so it must not be called from inside a Spring
     * {@code @Transactional} method: the document row saved earlier in that transaction is not
     * committed yet, posting cannot see it, and the result is register movements with
     * {@code _posted = false}.
     */
    public static final ArchRule POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS = methods()
            .that(areSpringTransactional())
            .should(notCallPosting())
            .because("onno posting (PostingService/PostingEngine/DocumentCommandService post, repost, "
                    + "unpost) runs in its own JDBI transaction that cannot see uncommitted rows of a "
                    + "surrounding Spring @Transactional. Save the document in one transactional "
                    + "method, let it commit, then post from a method that is not @Transactional "
                    + "(or use @Transactional(propagation = NOT_SUPPORTED))")
            .allowEmptyShould(true);

    /**
     * Request handlers must read and write through the record-scoped service layer
     * ({@code su.onno.service}, which takes an {@code AccessSubject}) or a Commands bean, never
     * through a typed repository or the trusted posting service directly.
     */
    public static final ArchRule CONTROLLERS_USE_SCOPED_SERVICES = noClasses()
            .that().areMetaAnnotatedWith(CONTROLLER)
            .should().dependOnClassesThat(isAnyOf(UNSCOPED_REPOSITORIES).or(isAnyOf(TRUSTED_POSTING)))
            .because("typed onno repositories and PostingService are unscoped: they ignore "
                    + "@AccessControl, RecordAccessPolicy and postRoles/unpostRoles. Serve requests "
                    + "through the su.onno.service query/command services with the request's "
                    + "AccessSubject (or a Commands<E> bean); if custom code must use a repository, "
                    + "move it into a service that first calls "
                    + "RecordAccess.require(subject, Type.class, id, AccessMode.READ)")
            .allowEmptyShould(true);

    /**
     * Catalogs, documents, rows, registers, constants and enumerations stay plain Java: no Spring
     * beans, no web/UI or service-layer types.
     */
    public static final ArchRule DOMAIN_MODEL_IS_FRAMEWORK_NEUTRAL = noClasses()
            .that(areOnnoDomainClasses())
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.beans..",
                    "org.springframework.context..",
                    "org.springframework.stereotype..",
                    "org.springframework.web..",
                    "org.springframework.http..",
                    "org.springframework.jdbc..",
                    "org.springframework.transaction..",
                    "org.springframework.boot..",
                    "org.springframework.security..",
                    "jakarta.servlet..",
                    "su.onno.web..",
                    "su.onno.service..",
                    "su.onno.spring..",
                    "su.onno.mcp..")
            .because("onno domain classes are plain Java: lifecycle hooks (beforeWrite, "
                    + "handlePosting, afterPost) have no Spring access, and UI concerns belong to the "
                    + "React app. Put behaviour that needs beans in a Commands<E>/Checks<E> bean or a "
                    + "@EventListener for DocumentPostedEvent, and presentation in the entity's "
                    + "EntityCustomization in src/main/ui")
            .allowEmptyShould(true);

    /**
     * A field of a catalog, document, row or register that points at another catalog or document
     * must be a {@code Ref<T>} (or {@code PolyRef}), never the record object itself.
     */
    public static final ArchRule RECORDS_REFERENCE_RECORDS_BY_REF = fields()
            .that().areDeclaredInClassesThat(areOnnoDomainClasses())
            .and().areNotStatic()
            .should(notHoldRecordObjects())
            .because("onno stores a reference as the target's id: declare the field as Ref<Target> "
                    + "(or PolyRef with @RefTargets for several target types) and resolve it when "
                    + "needed; an embedded CatalogObject/DocumentObject is not a reference")
            .allowEmptyShould(true);

    /**
     * A method promising a plain {@code List} returns the complete result; reading one keyset
     * window's {@code rows()} silently truncates at the window size.
     */
    public static final ArchRule FULL_LISTS_DRAIN_KEYSET_WINDOWS = methods()
            .that().haveRawReturnType(List.class)
            .should(notTruncateToOneKeysetWindow())
            .because("a method returning a plain List must return the complete result: implement it "
                    + "as KeysetWindow.drain(c -> page(..., c, KeysetWindow.MAX_LIMIT)) instead of "
                    + "returning the first window's rows(), or return the KeysetWindow.Page itself")
            .allowEmptyShould(true);

    /**
     * Opt-in (not part of {@link #all()}): HTTP {@code GET} handlers must not return a bare
     * collection or array. A collection endpoint answers one keyset window,
     * {@code {rows, nextCursor, hasMore}} — return {@code su.onno.query.KeysetWindow.Page} or
     * {@code su.onno.service.KeysetWindow.envelope(page)}. Small bounded selectors are legitimate
     * exceptions; freeze them with {@code FreezingArchRule.freeze(...)}.
     */
    public static final ArchRule COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS = methods()
            .that(areGetHandlers())
            .should(notReturnBareCollections())
            .because("collection endpoints return one keyset window {rows, nextCursor, hasMore}: "
                    + "page with su.onno.query.KeysetWindow and return the Page or "
                    + "su.onno.service.KeysetWindow.envelope(page), never a bare List or a bespoke "
                    + "entries/items/before shape. A truly bounded selector (it enforces its own "
                    + "maximum) is the only exception — freeze it explicitly")
            .allowEmptyShould(true);

    /** Every rule recommended for an onno application. */
    public static ArchRule all() {
        return CompositeArchRule.of(POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS)
                .and(CONTROLLERS_USE_SCOPED_SERVICES)
                .and(DOMAIN_MODEL_IS_FRAMEWORK_NEUTRAL)
                .and(RECORDS_REFERENCE_RECORDS_BY_REF)
                .and(FULL_LISTS_DRAIN_KEYSET_WINDOWS)
                .allowEmptyShould(true);
    }

    // --- predicates and conditions -------------------------------------------------------------

    /** Catalogs, documents, tabular rows, registers, constants and enumerations. */
    public static DescribedPredicate<JavaClass> areOnnoDomainClasses() {
        return new DescribedPredicate<>("are onno domain classes") {
            @Override
            public boolean test(JavaClass javaClass) {
                for (String base : DOMAIN_BASE_TYPES) {
                    if (javaClass.isAssignableTo(base) && !javaClass.getName().equals(base)) return true;
                }
                for (String annotation : DOMAIN_ANNOTATIONS) {
                    if (javaClass.isAnnotatedWith(annotation)) return true;
                }
                return false;
            }
        };
    }

    /** Methods that run inside a Spring/JTA transaction by their own or their class's annotation. */
    public static DescribedPredicate<JavaMethod> areSpringTransactional() {
        return new DescribedPredicate<>("are @Transactional") {
            @Override
            public boolean test(JavaMethod method) {
                Optional<JavaAnnotation<?>> annotation = transactionalAnnotation(method.getAnnotations());
                if (annotation.isEmpty()) {
                    annotation = transactionalAnnotation(method.getOwner().getAnnotations());
                }
                return annotation.isPresent() && joinsOrStartsTransaction(annotation.get());
            }
        };
    }

    private static Optional<JavaAnnotation<?>> transactionalAnnotation(
            Set<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (TRANSACTIONAL.contains(annotation.getRawType().getName())) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    /** {@code NOT_SUPPORTED}/{@code NEVER} suspend or forbid a transaction: posting is safe there. */
    private static boolean joinsOrStartsTransaction(JavaAnnotation<?> annotation) {
        for (String property : List.of("propagation", "value")) {
            Optional<Object> value = annotation.get(property);
            if (value.isPresent() && value.get() instanceof JavaEnumConstant constant
                    && (constant.name().equals("NOT_SUPPORTED") || constant.name().equals("NEVER"))) {
                return false;
            }
        }
        return true;
    }

    private static ArchCondition<JavaMethod> notCallPosting() {
        return new ArchCondition<>("not post, repost or unpost documents") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    AccessTarget.MethodCallTarget target = call.getTarget();
                    if (POSTING_METHODS.contains(target.getName()) && isAnyOf(POSTING_OWNERS).test(target.getOwner())) {
                        events.add(SimpleConditionEvent.violated(call,
                                "@Transactional " + method.getFullName() + " posts: " + call.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaField> notHoldRecordObjects() {
        return new ArchCondition<>("not hold catalog/document objects") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (holdsRecord(field.getType())) {
                    events.add(SimpleConditionEvent.violated(field, field.getFullName()
                            + " holds a record object of type " + field.getType().getName()
                            + " instead of a Ref"));
                }
            }
        };
    }

    private static boolean holdsRecord(JavaType type) {
        JavaClass raw = type.toErasure();
        if (raw.isArray()) return holdsRecord(raw.getBaseComponentType());
        if (isAnyOf(RECORD_TYPES).test(raw)) return true;
        boolean container = raw.isAssignableTo(java.util.Collection.class)
                || raw.isAssignableTo(java.util.Map.class)
                || raw.isAssignableTo(Optional.class);
        if (container && type instanceof JavaParameterizedType parameterized) {
            for (JavaType argument : parameterized.getActualTypeArguments()) {
                if (holdsRecord(argument)) return true;
            }
        }
        return false;
    }

    private static ArchCondition<JavaMethod> notTruncateToOneKeysetWindow() {
        return new ArchCondition<>("not return a single keyset window's rows") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getOwner().getName().equals(KEYSET_WINDOW)) return;
                // Draining, or following nextCursor/hasMore by hand, reads every window.
                boolean followsCursor = method.getMethodCallsFromSelf().stream().anyMatch(call ->
                        (call.getTarget().getOwner().getName().equals(KEYSET_WINDOW)
                                && call.getTarget().getName().equals("drain"))
                                || (call.getTarget().getOwner().getName().equals(KEYSET_PAGE)
                                && Set.of("nextCursor", "hasMore").contains(call.getTarget().getName())));
                if (followsCursor) return;
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (call.getTarget().getOwner().getName().equals(KEYSET_PAGE)
                            && call.getTarget().getName().equals("rows")) {
                        events.add(SimpleConditionEvent.violated(call, method.getFullName()
                                + " returns a List but reads one window: " + call.getDescription()));
                    }
                }
            }
        };
    }

    private static DescribedPredicate<JavaMethod> areGetHandlers() {
        return new DescribedPredicate<>("are HTTP GET handlers of controllers") {
            @Override
            public boolean test(JavaMethod method) {
                if (!method.getOwner().isMetaAnnotatedWith(CONTROLLER)) return false;
                if (method.isAnnotatedWith(GET_MAPPING)) return true;
                Optional<? extends JavaAnnotation<?>> mapping = method.tryGetAnnotationOfType(REQUEST_MAPPING);
                if (mapping.isEmpty()) return false;
                Optional<Object> verbs = mapping.get().get("method");
                if (verbs.isEmpty() || !(verbs.get() instanceof Object[] array)) return false;
                for (Object verb : array) {
                    if (verb instanceof JavaEnumConstant constant && constant.name().equals("GET")) return true;
                }
                return false;
            }
        };
    }

    private static ArchCondition<JavaMethod> notReturnBareCollections() {
        return new ArchCondition<>("not return a bare collection") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (isBareCollection(method.getReturnType())) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName()
                            + " returns a bare collection (" + method.getReturnType().getName() + ")"
                            + " in " + method.getSourceCodeLocation()));
                }
            }
        };
    }

    private static boolean isBareCollection(JavaType type) {
        JavaClass raw = type.toErasure();
        if (raw.getName().equals(RESPONSE_ENTITY) && type instanceof JavaParameterizedType parameterized
                && !parameterized.getActualTypeArguments().isEmpty()) {
            return isBareCollection(parameterized.getActualTypeArguments().get(0));
        }
        if (raw.isArray()) return !raw.getComponentType().getName().equals("byte");
        return raw.isAssignableTo(Iterable.class)
                || raw.isAssignableTo(java.util.stream.Stream.class);
    }

    private static DescribedPredicate<JavaClass> isAnyOf(List<String> typeNames) {
        return new DescribedPredicate<>("are assignable to any of " + typeNames) {
            @Override
            public boolean test(JavaClass javaClass) {
                for (String name : typeNames) {
                    if (javaClass.isAssignableTo(name)) return true;
                }
                return false;
            }
        };
    }
}
