package com.onec.spring.fixtures;

import com.onec.annotations.Enumeration;

@Enumeration(name = "ServiceCategories")
public enum TestServiceCategory {
    VACCINATION,
    SURGERY,
    GROOMING
}
