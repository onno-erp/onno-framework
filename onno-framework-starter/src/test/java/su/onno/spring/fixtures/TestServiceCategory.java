package su.onno.spring.fixtures;

import su.onno.annotations.Enumeration;

@Enumeration(name = "ServiceCategories")
public enum TestServiceCategory {
    VACCINATION,
    SURGERY,
    GROOMING
}
