package com.onec.spring;

import com.onec.model.AccumulationRecord;
import com.onec.repository.RegisterRepository;

import java.util.Map;

public class RegisterRepositoryProvider {

    private final Map<Class<?>, RegisterRepository<?>> repositories;

    public RegisterRepositoryProvider(Map<Class<?>, RegisterRepository<?>> repositories) {
        this.repositories = repositories;
    }

    @SuppressWarnings("unchecked")
    public <T extends AccumulationRecord> RegisterRepository<T> forRegister(Class<T> registerClass) {
        RegisterRepository<?> repo = repositories.get(registerClass);
        if (repo == null) {
            throw new IllegalArgumentException(
                    "No RegisterRepository found for " + registerClass.getName());
        }
        return (RegisterRepository<T>) repo;
    }
}
