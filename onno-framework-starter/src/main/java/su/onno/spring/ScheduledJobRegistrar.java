package su.onno.spring;

import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundTask;

import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates scheduled task declarations before registering any recurring jobs. */
public class ScheduledJobRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRegistrar.class);

    private final ApplicationContext applicationContext;
    private final JobScheduler jobScheduler;
    private final List<String> scanPackages;

    public ScheduledJobRegistrar(ApplicationContext applicationContext, JobScheduler jobScheduler) {
        this(applicationContext, jobScheduler, List.of());
    }

    public ScheduledJobRegistrar(ApplicationContext applicationContext, JobScheduler jobScheduler,
                                 List<String> scanPackages) {
        this.applicationContext = applicationContext;
        this.jobScheduler = jobScheduler;
        this.scanPackages = List.copyOf(scanPackages);
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> tasks = new LinkedHashMap<>();
        applicationContext.getBeansOfType(BackgroundTask.class).forEach((name, task) -> {
            if (AnnotatedElementUtils.findMergedAnnotation(AopUtils.getTargetClass(task), ScheduledJob.class) != null) {
                tasks.put(name, task);
            }
        });
        tasks.putAll(applicationContext.getBeansWithAnnotation(ScheduledJob.class));
        validateDeclarations(tasks);
        for (Object task : tasks.values()) {
            ScheduledJob annotation = AnnotatedElementUtils.findMergedAnnotation(
                    AopUtils.getTargetClass(task), ScheduledJob.class);
            if (!(task instanceof BackgroundTask)) {
                throw new IllegalStateException("@ScheduledJob '" + annotation.name() + "' on "
                        + AopUtils.getTargetClass(task).getName() + " must implement BackgroundTask");
            }
            if (jobScheduler == null) {
                throw new IllegalStateException("@ScheduledJob '" + annotation.name() + "' on "
                        + AopUtils.getTargetClass(task).getName()
                        + " requires a JobRunr JobScheduler bean. Configure JobRunr before declaring scheduled jobs.");
            }
        }
        for (Object bean : tasks.values()) {
            ScheduledJob annotation = AnnotatedElementUtils.findMergedAnnotation(
                    AopUtils.getTargetClass(bean), ScheduledJob.class);
            BackgroundTask task = (BackgroundTask) bean;
            log.info("Registering scheduled job: {} with cron: {}", annotation.name(), annotation.cron());
            jobScheduler.scheduleRecurrently(annotation.name(), annotation.cron(), task::execute);
        }
    }

    private void validateDeclarations(Map<String, Object> tasks) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false, applicationContext.getEnvironment());
        scanner.setResourceLoader(applicationContext);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ScheduledJob.class));
        for (String pkg : scanPackages) {
            for (var definition : scanner.findCandidateComponents(pkg)) {
                Class<?> type;
                try {
                    type = ClassUtils.forName(definition.getBeanClassName(), applicationContext.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Failed to load scheduled job: " + definition.getBeanClassName(), e);
                }
                boolean registered = tasks.values().stream()
                        .anyMatch(task -> type.isAssignableFrom(AopUtils.getTargetClass(task)));
                if (!registered) {
                    ScheduledJob annotation = AnnotatedElementUtils.findMergedAnnotation(type, ScheduledJob.class);
                    throw new IllegalStateException("@ScheduledJob '" + annotation.name() + "' on " + type.getName()
                            + " is not a Spring bean. Add @Component within component scanning or declare it with @Bean.");
                }
            }
        }
    }
}
