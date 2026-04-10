package com.onec.posting;

import com.onec.model.AccumulationRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RegisterMovementCollection<T extends AccumulationRecord> {

    private final Class<T> registerClass;
    private final List<T> records = new ArrayList<>();

    public RegisterMovementCollection(Class<T> registerClass) {
        this.registerClass = registerClass;
    }

    public T add() {
        try {
            T record = registerClass.getDeclaredConstructor().newInstance();
            records.add(record);
            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create register record instance", e);
        }
    }

    public List<T> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public Class<T> getRegisterClass() {
        return registerClass;
    }
}
