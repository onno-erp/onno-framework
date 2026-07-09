package su.onno.ui;

import org.springframework.beans.factory.DisposableBean;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs a bulk row action over a set of ids and reports {@code {ok, failed, total}}. The list's
 * multi-row selection posts every selected id in one request; each id is handled independently — its
 * own transaction, continue-on-failure — so the set is resolved concurrently on a bounded pool.
 * {@code onno.ui.batch.parallelism} caps the fan-out ({@code 1} runs inline, exactly the old
 * sequential behaviour). Shared by the action-batch and the catalog/document batch-delete endpoints.
 *
 * <p>Spring Security is an optional dependency of this module (see {@link UiAccessService}), so the
 * caller's {@code SecurityContext} is propagated to worker threads reflectively — a no-op when
 * Security isn't on the classpath — letting consumer action handlers that read
 * {@code SecurityContextHolder} keep working under fan-out. The authenticated {@link
 * java.security.Principal} is also passed to handlers explicitly by the callers, so the framework's
 * own write path never relies on this.
 */
public class BatchRunner implements DisposableBean {

    /** {@code null} => run inline on the request thread (parallelism &le; 1). */
    private final ExecutorService pool;

    public BatchRunner(int parallelism) {
        int p = Math.max(1, parallelism);
        this.pool = p == 1 ? null : Executors.newFixedThreadPool(p, r -> {
            Thread t = new Thread(r, "onno-batch-action");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Apply {@code perId} to every id and tally the outcome. A per-id {@link RuntimeException} is
     * caught and that id recorded in {@code failed} (order-stable, in the input order) while the rest
     * of the batch proceeds. Returns {@code {ok, failed, total}}.
     */
    public Map<String, Object> run(List<UUID> ids, Consumer<UUID> perId) {
        // A slot per id: null == succeeded, else the failed id string. Fixed-size and index-addressed,
        // so workers never contend on a shared collection and the failed list stays in input order.
        String[] failedAt = new String[ids.size()];
        AtomicInteger ok = new AtomicInteger();

        if (pool == null) {
            for (int i = 0; i < ids.size(); i++) {
                runOne(ids.get(i), perId, failedAt, i, ok);
            }
        } else {
            Object ctx = SecurityContextRelay.capture();
            List<Future<?>> futures = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                final int idx = i;
                final UUID id = ids.get(i);
                futures.add(pool.submit(() -> {
                    Object prev = SecurityContextRelay.install(ctx);
                    try {
                        runOne(id, perId, failedAt, idx, ok);
                    } finally {
                        SecurityContextRelay.restore(prev);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Batch interrupted", e);
                } catch (ExecutionException e) {
                    // runOne swallows per-id RuntimeExceptions, so anything surfacing here is a fault in
                    // the harness itself (not a per-id failure) — propagate it rather than hide it.
                    Throwable cause = e.getCause();
                    throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
                }
            }
        }

        List<String> failed = new ArrayList<>();
        for (String s : failedAt) {
            if (s != null) {
                failed.add(s);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok.get());
        out.put("failed", failed);
        out.put("total", ids.size());
        return out;
    }

    private static void runOne(UUID id, Consumer<UUID> perId, String[] failedAt, int idx, AtomicInteger ok) {
        try {
            perId.accept(id);
            ok.incrementAndGet();
        } catch (RuntimeException e) {
            failedAt[idx] = id.toString();
        }
    }

    @Override
    public void destroy() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /**
     * Best-effort, reflection-only bridge to {@code SecurityContextHolder}. Spring Security is not on
     * this module's compile classpath (test-only), so we bind its statics reflectively at class load
     * and degrade to no-ops when the class is absent.
     */
    private static final class SecurityContextRelay {

        private static final Method GET;
        private static final Method SET;
        private static final Method CLEAR;

        static {
            Method get = null, set = null, clear = null;
            try {
                Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
                Class<?> ctxType = Class.forName("org.springframework.security.core.context.SecurityContext");
                get = holder.getMethod("getContext");
                set = holder.getMethod("setContext", ctxType);
                clear = holder.getMethod("clearContext");
            } catch (Throwable ignored) {
                // Security absent — propagation is a no-op; handlers get the Principal explicitly.
            }
            GET = get;
            SET = set;
            CLEAR = clear;
        }

        /** Snapshot the request thread's SecurityContext to hand to workers. */
        static Object capture() {
            if (GET == null) {
                return null;
            }
            try {
                return GET.invoke(null);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        /** Install {@code ctx} on the current (worker) thread; returns the prior context to restore. */
        static Object install(Object ctx) {
            if (GET == null || SET == null || ctx == null) {
                return null;
            }
            try {
                Object prev = GET.invoke(null);
                SET.invoke(null, ctx);
                return prev;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        static void restore(Object prev) {
            if (SET == null || CLEAR == null) {
                return;
            }
            try {
                if (prev != null) {
                    SET.invoke(null, prev);
                } else {
                    CLEAR.invoke(null);
                }
            } catch (ReflectiveOperationException ignored) {
                // Nothing actionable — the worker thread is about to be reused or discarded.
            }
        }
    }
}
