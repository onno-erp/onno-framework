package com.onec.posting;

import com.onec.model.AccumulationRecord;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PostingContext {

    private final Map<Class<?>, RegisterMovementCollection<?>> collections = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T extends AccumulationRecord> RegisterMovementCollection<T> movements(Class<T> registerClass) {
        return (RegisterMovementCollection<T>) collections.computeIfAbsent(
                registerClass,
                k -> new RegisterMovementCollection<>(registerClass)
        );
    }

    public Collection<RegisterMovementCollection<?>> allMovements() {
        return collections.values();
    }
}
