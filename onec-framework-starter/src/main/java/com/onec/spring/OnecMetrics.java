package com.onec.spring;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public class OnecMetrics {

    public static final String DURATION_METER = "onec.operation.duration";
    public static final String ITEMS_METER = "onec.operation.items";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> itemSummaries = new ConcurrentHashMap<>();

    public OnecMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T time(String operation, long itemCount, Supplier<T> action) {
        if (registry == null) {
            return action.get();
        }
        Timer.Sample sample = Timer.start(registry);
        try {
            return action.get();
        } finally {
            sample.stop(timers.computeIfAbsent(operation, this::timer));
            if (itemCount > 0) {
                itemSummaries.computeIfAbsent(operation, this::itemSummary).record(itemCount);
            }
        }
    }

    public void time(String operation, long itemCount, Runnable action) {
        time(operation, itemCount, () -> {
            action.run();
            return null;
        });
    }

    private Timer timer(String operation) {
        return Timer.builder(DURATION_METER)
                .description("Duration of onec framework operations")
                .tag("operation", operation)
                .register(registry);
    }

    private DistributionSummary itemSummary(String operation) {
        return DistributionSummary.builder(ITEMS_METER)
                .description("Number of items processed by onec framework operations")
                .tag("operation", operation)
                .baseUnit("items")
                .register(registry);
    }
}
