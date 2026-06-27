package com.example.repositories;

import com.example.domain.catalogs.Book;
import su.onno.repository.CatalogRepository;

/** Typed repository for {@link Book}. */
public interface BookRepository extends CatalogRepository<Book> {
}
