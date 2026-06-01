package com.onec.spring;

import org.springframework.data.repository.core.support.AbstractEntityInformation;

import java.util.UUID;

public class RegisterEntityInformation<T, ID> extends AbstractEntityInformation<T, ID> {

    public RegisterEntityInformation(Class<T> domainClass) {
        super(domainClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ID getId(T entity) {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<ID> getIdType() {
        return (Class<ID>) UUID.class;
    }
}
