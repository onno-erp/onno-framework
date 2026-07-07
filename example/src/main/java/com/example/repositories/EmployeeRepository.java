package com.example.repositories;

import com.example.domain.catalogs.Employee;
import su.onno.repository.CatalogRepository;

/** Typed repository for {@link Employee}. */
public interface EmployeeRepository extends CatalogRepository<Employee> {
}
