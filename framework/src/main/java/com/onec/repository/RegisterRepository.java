package com.onec.repository;

import com.onec.model.AccumulationRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface RegisterRepository<T extends AccumulationRecord> {

    List<T> getBalance();

    List<T> getBalance(Map<String, Object> filters);

    List<T> getTurnover(LocalDateTime from, LocalDateTime to);

    List<T> getTurnover(LocalDateTime from, LocalDateTime to, Map<String, Object> filters);

    List<T> getRecordsByDocument(UUID documentRef);
}
