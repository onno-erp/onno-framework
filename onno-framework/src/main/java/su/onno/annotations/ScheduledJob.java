package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a recurring JobRunr task. The annotated class must implement
 * {@link su.onno.jobs.BackgroundTask} and be registered as a Spring bean
 * (for example with {@code @Component} or {@code @Bean}). A JobRunr
 * {@code JobScheduler} bean is required. The starter validates declarations
 * in its scan packages at startup and fails if a task cannot be registered.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ScheduledJob {

    String name();

    String cron();
}
