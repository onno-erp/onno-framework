package com.onec.repository;

import com.onec.model.DocumentObject;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface DocumentRepository<T extends DocumentObject> extends ListCrudRepository<T, UUID> {

    Optional<T> findByNumber(String number);

    List<T> findByDateBetween(LocalDateTime from, LocalDateTime to);
}
