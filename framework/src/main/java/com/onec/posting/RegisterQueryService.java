package com.onec.posting;

import com.onec.model.AccumulationRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RegisterQueryService {

    private final Map<Class<?>, RegisterPersistence<?>> persistenceMap;

    public RegisterQueryService(Map<Class<?>, RegisterPersistence<?>> persistenceMap) {
        this.persistenceMap = persistenceMap;
    }

    public List<Map<String, Object>> getBalance(Class<? extends AccumulationRecord> registerClass,
                                                  Map<String, Object> filters) {
        return getPersistence(registerClass).getBalance(filters);
    }

    public List<Map<String, Object>> getTurnover(Class<? extends AccumulationRecord> registerClass,
                                                   LocalDateTime from, LocalDateTime to,
                                                   Map<String, Object> filters) {
        return getPersistence(registerClass).getTurnover(from, to, filters);
    }

    @SuppressWarnings("unchecked")
    public <T extends AccumulationRecord> List<T> getRecords(Class<T> registerClass,
                                                              UUID documentRef) {
        return ((RegisterPersistence<T>) getPersistence(registerClass))
                .getRecordsByDocument(documentRef);
    }

    private RegisterPersistence<?> getPersistence(Class<?> registerClass) {
        RegisterPersistence<?> persistence = persistenceMap.get(registerClass);
        if (persistence == null) {
            throw new IllegalArgumentException(
                    "No register persistence for " + registerClass.getName());
        }
        return persistence;
    }
}
