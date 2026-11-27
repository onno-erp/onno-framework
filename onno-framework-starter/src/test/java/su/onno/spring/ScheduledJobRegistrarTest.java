package su.onno.spring;

import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;
import su.onno.spring.fixtures.scheduledjobs.managed.ManagedTask;
import su.onno.spring.fixtures.scheduledjobs.missing.MissingTask;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScheduledJobRegistrarTest {
    private static final String ROOT = "su.onno.spring.fixtures.scheduledjobs.";

    @Test
    void missingBeanFailsStartupWithActionableMessage() {
        try (var context = context("missing", mock(JobScheduler.class))) {
            assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
                    .hasRootCauseMessage("@ScheduledJob 'MissingTask' on " + MissingTask.class.getName()
                            + " is not a Spring bean. Add @Component within component scanning or declare it with @Bean.");
        }
    }

    @Test
    void defaultBootApplicationPackagesAreValidatedWithoutScheduler() {
        try (var context = new AnnotationConfigApplicationContext()) {
            org.springframework.boot.autoconfigure.AutoConfigurationPackages.register(
                    context, ROOT + "missing");
            context.registerBean(ScheduledJobRegistrar.class, () -> new OnnoAutoConfiguration()
                    .scheduledJobRegistrar(context, context.getBeanProvider(JobScheduler.class), new OnnoProperties()));
            assertThatThrownBy(context::refresh).hasStackTraceContaining("MissingTask")
                    .hasStackTraceContaining("is not a Spring bean");
        }
    }

    @Test
    void missingSchedulerFailsStartup() {
        try (var context = context("managed", null)) {
            context.register(ManagedTask.class);
            assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
                    .hasStackTraceContaining("ManagedTask").hasStackTraceContaining("requires a JobRunr JobScheduler bean");
        }
    }

    @Test
    void componentIsRegisteredExactlyOnceEvenWithRepeatedScanPackages() {
        JobScheduler scheduler = mock(JobScheduler.class);
        try (var context = context("managed", scheduler)) {
            context.scan(ROOT + "managed");
            context.refresh();
            verify(scheduler).scheduleRecurrently(eq("ManagedTask"), eq("0 0 2 * * *"), any(JobLambda.class));
            verifyNoMoreInteractions(scheduler);
        }
    }

    @Test
    void explicitBeanNeedsNoComponentAndKeepsItsInjectedDependency() throws Exception {
        JobScheduler scheduler = mock(JobScheduler.class);
        Runnable dependency = mock(Runnable.class);
        try (var context = context("missing", scheduler)) {
            // Register the unannotated-as-component declaration explicitly, as an @Bean method would.
            context.registerBean(MissingTask.class);
            context.registerBean(InjectedTask.class, () -> new InjectedTask(dependency));
            context.refresh();
            var callback = org.mockito.ArgumentCaptor.forClass(JobLambda.class);
            verify(scheduler).scheduleRecurrently(eq("injected"), eq("0 0 2 * * *"), callback.capture());
            callback.getValue().run();
            verify(dependency).run();
            verify(scheduler).scheduleRecurrently(eq("MissingTask"), eq("0 0 2 * * *"), any(JobLambda.class));
            verifyNoMoreInteractions(scheduler);
        }
    }

    @Test
    void jdkProxyRetainsScheduledJobAnnotationAndExecutionAdvice() throws Exception {
        JobScheduler scheduler = mock(JobScheduler.class);
        Runnable dependency = mock(Runnable.class);
        Runnable advice = mock(Runnable.class);
        try (var context = context("disabled", scheduler)) {
            var factory = new ProxyFactory(new InjectedTask(dependency));
            factory.setInterfaces(BackgroundTask.class);
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                advice.run();
                return invocation.proceed();
            });
            context.registerBean("proxiedTask", BackgroundTask.class, () -> (BackgroundTask) factory.getProxy());
            context.refresh();
            var callback = org.mockito.ArgumentCaptor.forClass(JobLambda.class);
            verify(scheduler).scheduleRecurrently(eq("injected"), eq("0 0 2 * * *"), callback.capture());
            callback.getValue().run();
            verify(dependency).run();
            verify(advice).run();
        }
    }

    @Test
    void noJobsNeedsNoSchedulerAndInactiveProfilesAreIgnored() {
        try (var context = context("disabled", null)) {
            context.refresh();
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void annotatedBeanMustImplementBackgroundTaskAndNothingIsScheduledOnValidationFailure() {
        JobScheduler scheduler = mock(JobScheduler.class);
        try (var context = context("disabled", scheduler)) {
            context.register(ManagedTask.class, InvalidTask.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("must implement BackgroundTask");
            verifyNoInteractions(scheduler);
        }
    }

    @Test
    void realJobRunrStoresRecurringJob() {
        try (var storage = new org.jobrunr.storage.InMemoryStorageProvider();
             var context = context("managed", new JobScheduler(storage))) {
            storage.setJobMapper(new org.jobrunr.jobs.mappers.JobMapper(
                    new org.jobrunr.utils.mapper.jackson.JacksonJsonMapper()));
            context.scan(ROOT + "managed");
            context.refresh();
            assertThat(storage.getRecurringJobs()).hasSize(1);
            assertThat(storage.getRecurringJobs().get(0).getId()).isEqualTo("ManagedTask");
        }
    }

    @Test
    void actualBeanMethodMayReturnBackgroundTaskInterface() {
        var scheduler = mock(JobScheduler.class);
        try (var context = context("missing", scheduler)) {
            context.register(TaskBeans.class);
            context.refresh();
            verify(scheduler).scheduleRecurrently(eq("MissingTask"), eq("0 0 2 * * *"), any(JobLambda.class));
            verifyNoMoreInteractions(scheduler);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class TaskBeans {
        @org.springframework.context.annotation.Bean
        BackgroundTask task() { return new MissingTask(); }
    }

    @Test
    void realAutoConfigurationValidatesMissingScheduler() {
        try (var context = autoConfiguredContext("managed")) {
            context.register(ManagedTask.class);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("requires a JobRunr JobScheduler bean");
        }
    }

    @Test
    void realAutoConfigurationStartsWithoutJobsOrScheduler() {
        try (var context = autoConfiguredContext("disabled")) {
            context.refresh();
            assertThat(context.getBeansOfType(JobScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledJobRegistrar.class)).hasSize(1);
        }
    }

    @Test
    void realAutoConfigurationRejectsUnmanagedJobEvenWithScheduler() {
        try (var context = autoConfiguredContext("missing")) {
            var scheduler = mock(JobScheduler.class);
            context.registerBean(JobScheduler.class, () -> scheduler);
            assertThatThrownBy(context::refresh).hasStackTraceContaining("MissingTask")
                    .hasStackTraceContaining("is not a Spring bean");
        }
    }

    private AnnotationConfigApplicationContext autoConfiguredContext(String fixture) {
        var context = new AnnotationConfigApplicationContext();
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:scheduled-job-" + fixture + ";DB_CLOSE_DELAY=-1");
        context.registerBean(javax.sql.DataSource.class, () -> source);
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test", java.util.Map.of(
                        "onno.scan-packages", ROOT + fixture)));
        context.register(org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class,
                OnnoAutoConfiguration.class);
        return context;
    }

    private AnnotationConfigApplicationContext context(String fixture, JobScheduler scheduler) {
        var context = new AnnotationConfigApplicationContext();
        if (scheduler != null) context.registerBean(JobScheduler.class, () -> scheduler);
        var properties = new OnnoProperties();
        properties.setScanPackages(List.of(ROOT + fixture, ROOT + fixture));
        context.registerBean(ScheduledJobRegistrar.class, () -> new OnnoAutoConfiguration()
                .scheduledJobRegistrar(context, context.getBeanProvider(JobScheduler.class), properties));
        return context;
    }

    @ScheduledJob(name = "injected", cron = "0 0 2 * * *")
    static class InjectedTask implements BackgroundTask {
        private final Runnable dependency;
        InjectedTask(Runnable dependency) { this.dependency = dependency; }
        @Override public void execute() { dependency.run(); }
    }

    @ScheduledJob(name = "invalid", cron = "0 0 2 * * *")
    static class InvalidTask {}
}
