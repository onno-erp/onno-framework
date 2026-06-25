package su.onno.spring;

import su.onno.repository.CatalogRepository;
import su.onno.repository.DocumentRepository;
import su.onno.repository.IncludesDeleted;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.support.Repositories;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Boot-time guardrail against the soft-delete footgun. Catalogs/documents are soft-deleted
 * ({@code deletionMark}); the inherited repository finders ({@code findAll()}/{@code findById()}/
 * {@code findByCode()}/{@code findByNumber()}) return those tombstones by design (so {@code RefResolver}
 * can resolve a {@code Ref<T>} to a deleted target, and restore/admin can reach them). A hand-written
 * finder that forgets to exclude them silently feeds "deleted" rows into business logic — auth/login
 * admission, posting, totals, picker option lists. Spring Data JDBC has no global soft-delete filter
 * (no JPA {@code @Where} equivalent), so this scans every {@link CatalogRepository}/{@link DocumentRepository}
 * at startup and flags any <em>consumer-declared</em> finder that returns entities without being
 * deletion-scoped.
 *
 * <p>A finder is considered safe when its name carries a {@code DeletionMark} predicate (e.g.
 * {@code findByExternalNumberAndDeletionMarkFalse}), its {@code @Query} text references the
 * {@code deletion_mark} column, or it is annotated {@link IncludesDeleted} to declare it intends to see
 * tombstones. The framework's own inherited finders ({@code findActiveBy*}, {@code findByCode}, …) are
 * never flagged.</p>
 *
 * <p>Controlled by {@code onno.repository.deletion-check}: {@code warn} (default — log a warning),
 * {@code strict} (fail startup), or {@code off}.</p>
 */
public class DeletionAwareFinderValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DeletionAwareFinderValidator.class);

    /** Inherited base interfaces whose finders are framework-owned and must not be flagged. */
    private static final Set<String> BASE_REPOSITORY_TYPES = Set.of(
            CatalogRepository.class.getName(), DocumentRepository.class.getName());

    public enum Mode {
        OFF, WARN, STRICT;

        public static Mode fromString(String value) {
            if (value == null || value.isBlank()) {
                return WARN;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off" -> OFF;
                case "warn" -> WARN;
                case "strict" -> STRICT;
                default -> throw new IllegalArgumentException(
                        "Unknown onno.repository.deletion-check value '" + value + "' (expected off|warn|strict)");
            };
        }
    }

    private final ListableBeanFactory beanFactory;
    private final Mode mode;

    public DeletionAwareFinderValidator(ListableBeanFactory beanFactory, Mode mode) {
        this.beanFactory = beanFactory;
        this.mode = mode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (mode == Mode.OFF) {
            return;
        }
        List<String> violations = new ArrayList<>();
        Repositories repositories = new Repositories(beanFactory);
        for (Class<?> domainType : repositories) {
            Optional<RepositoryInformation> info = repositories.getRepositoryInformationFor(domainType);
            if (info.isEmpty()) {
                continue;
            }
            Class<?> repoInterface = info.get().getRepositoryInterface();
            if (!CatalogRepository.class.isAssignableFrom(repoInterface)
                    && !DocumentRepository.class.isAssignableFrom(repoInterface)) {
                continue;
            }
            violations.addAll(findViolations(repoInterface, info.get().getDomainType()));
        }
        report(violations);
    }

    /** Apply the configured mode to the collected violations. Package-private for testing. */
    void report(List<String> violations) {
        if (violations.isEmpty()) {
            log.debug("onno.repository.deletion-check: all catalog/document finders are deletion-aware.");
            return;
        }
        String message = buildMessage(violations);
        if (mode == Mode.STRICT) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    private static String buildMessage(List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append(violations.size())
          .append(" repository finder(s) may return soft-deleted (deletionMark=true) rows into business logic.\n")
          .append("Each returns catalog/document entities but is not deletion-scoped. Either exclude deleted rows\n")
          .append("(name it ...AndDeletionMarkFalse / filter deletion_mark in its @Query / delegate to\n")
          .append("findAllActive()/findActiveBy*), or annotate it @su.onno.repository.IncludesDeleted if it must\n")
          .append("see tombstones (e.g. restore/admin, Ref resolution). Set onno.repository.deletion-check=off to\n")
          .append("disable this check.\n");
        for (String violation : violations) {
            sb.append("  - ").append(violation).append('\n');
        }
        return sb.toString();
    }

    /**
     * The consumer-declared, entity-returning finders on {@code repoInterface} that are not
     * deletion-scoped and not opted out with {@link IncludesDeleted}. Package-private for testing.
     */
    static List<String> findViolations(Class<?> repoInterface, Class<?> domainType) {
        List<String> violations = new ArrayList<>();
        for (Method method : repoInterface.getMethods()) {
            if (!isConsumerDeclared(method) || method.isDefault() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!returnsEntity(method, domainType)) {
                continue;
            }
            if (method.isAnnotationPresent(IncludesDeleted.class) || isDeletionAware(method)) {
                continue;
            }
            violations.add(repoInterface.getSimpleName() + "." + method.getName() + "(...)");
        }
        return violations;
    }

    /** True unless the method is inherited from a Spring Data or framework base repository interface. */
    private static boolean isConsumerDeclared(Method method) {
        String declaring = method.getDeclaringClass().getName();
        if (declaring.startsWith("org.springframework.data") || declaring.startsWith("java.")) {
            return false;
        }
        return !BASE_REPOSITORY_TYPES.contains(declaring);
    }

    /** Whether the method returns the entity type, directly or wrapped in a single-arg container. */
    private static boolean returnsEntity(Method method, Class<?> domainType) {
        Class<?> raw = method.getReturnType();
        if (domainType.isAssignableFrom(raw)) {
            return true;
        }
        if (Optional.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw)
                || java.util.stream.Stream.class.isAssignableFrom(raw) || isSliceOrPage(raw)) {
            Class<?> element = elementType(method.getGenericReturnType());
            return element != null && domainType.isAssignableFrom(element);
        }
        return false;
    }

    private static boolean isSliceOrPage(Class<?> raw) {
        String name = raw.getName();
        return name.equals("org.springframework.data.domain.Page")
                || name.equals("org.springframework.data.domain.Slice")
                || name.equals("org.springframework.data.domain.Window");
    }

    private static Class<?> elementType(Type genericReturnType) {
        if (genericReturnType instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    private static boolean isDeletionAware(Method method) {
        if (method.getName().contains("DeletionMark")) {
            return true;
        }
        String query = queryText(method);
        return query != null && query.toLowerCase(Locale.ROOT).contains("deletion_mark");
    }

    /** The value of a Spring Data {@code @Query} (matched by simple name, any package), or null. */
    private static String queryText(AnnotatedElement element) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals("Query")) {
                try {
                    Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                    if (value instanceof String text) {
                        return text;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Not the annotation shape we expected — treat as no query text.
                }
            }
        }
        return null;
    }
}
