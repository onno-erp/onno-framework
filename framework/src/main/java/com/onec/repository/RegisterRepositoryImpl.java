package com.onec.repository;

import com.onec.model.AccumulationRecord;
import com.onec.posting.RegisterPersistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RegisterRepositoryImpl<T extends AccumulationRecord> implements RegisterRepository<T> {

    private final RegisterPersistence<T> persistence;

    public RegisterRepositoryImpl(RegisterPersistence<T> persistence) {
        this.persistence = persistence;
    }

    @Override
    public List<T> getBalance() {
        return persistence.getBalanceTyped(null);
    }

    @Override
    public List<T> getBalance(Map<String, Object> filters) {
        return persistence.getBalanceTyped(filters);
    }

    @Override
    public List<T> getTurnover(LocalDateTime from, LocalDateTime to) {
        return persistence.getTurnoverTyped(from, to, null);
    }

    @Override
    public List<T> getTurnover(LocalDateTime from, LocalDateTime to, Map<String, Object> filters) {
        return persistence.getTurnoverTyped(from, to, filters);
    }

    @Override
    public List<T> getRecordsByDocument(UUID documentRef) {
        return persistence.getRecordsByDocument(documentRef);
    }
}
