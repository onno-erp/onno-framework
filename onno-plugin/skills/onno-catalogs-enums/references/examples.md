# Catalogs And Enums Examples

## Table Of Contents

- Catalog With References
- Hierarchical Catalog
- Enumeration With Labels And Colors
- Secret Attributes
- Repository And Soft Delete
- Catalog Or Enum

## Catalog With References

```java
package com.acme.sales.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import java.math.BigDecimal;

@Catalog(name = "Products", title = "Products", codePrefix = "P-", context = "Catalog")
@AccessControl(readRoles = {"SALES", "ADMIN"}, writeRoles = {"ADMIN"})
@Getter
@Setter
public class Product extends CatalogObject {

    @Attribute(displayName = "Name", required = true, length = 200)
    private String name;

    @Attribute(displayName = "Group")
    private Ref<ProductGroup> group;

    @Attribute(displayName = "Default supplier")
    private Ref<Supplier> supplier;

    @Attribute(displayName = "Unit price", precision = 15, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Attribute(displayName = "Image URL", length = 500)
    private String imageUrl;
}
```

Notes:

- `code`, `description`, `id`, `deletionMark`, and `isNew` come from `CatalogObject`.
- If users search by a human name, decide whether to use inherited `description`, a custom `name`,
  or both. Keep list/form labels explicit in the `EntityView`.
- `Ref<ProductGroup>` stores only the target UUID. Resolve it with `RefResolver` only when you need
  the full target object in Java logic.

## Hierarchical Catalog

Use hierarchy when users organize a list into folders or parent-child trees.

```java
@Catalog(name = "Product Groups", title = "Product groups", hierarchical = true,
        codePrefix = "G-", context = "Catalog")
@AccessControl(readRoles = {"SALES", "ADMIN"}, writeRoles = {"ADMIN"})
@Getter
@Setter
public class ProductGroup extends CatalogObject {
}
```

`CatalogObject` already has `folder` and `parent`. Do not add your own parent column unless the
business has a second, distinct hierarchy.

## Enumeration With Labels And Colors

```java
package com.acme.sales.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "Order Statuses", title = "Order status")
public enum OrderStatus {
    @EnumLabel(value = "Draft", color = "#6B7280")
    DRAFT,

    @EnumLabel(value = "Confirmed", color = "#2563EB")
    CONFIRMED,

    @EnumLabel(value = "Shipped", color = "#7C3AED")
    SHIPPED,

    @EnumLabel(value = "Completed", color = "#059669")
    COMPLETED,

    @EnumLabel(value = "Cancelled", color = "#DC2626")
    CANCELLED
}
```

Keep enum constants stable. Stored UUIDs and import/filter mappings depend on constants, not labels.
Change labels freely; do not rename constants without a migration plan.

## Secret Attributes

```java
@Catalog(name = "Api Accounts", title = "API accounts", codePrefix = "API-")
@AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
@Getter
@Setter
public class ApiAccount extends CatalogObject {

    @Attribute(displayName = "Provider", required = true)
    private String provider;

    @Attribute(displayName = "API key", secret = true, length = 2000)
    private String apiKey;
}
```

Secret attributes are encrypted at rest when `onno.security.secret-key` is configured and read back
through APIs as `__SECRET_SET__`. Do not use a secret field when business logic needs to compare or
filter by the raw value.

## Repository And Soft Delete

```java
package com.acme.sales.repositories;

import com.acme.sales.domain.Product;
import org.springframework.stereotype.Repository;
import su.onno.repository.CatalogRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends CatalogRepository<Product> {
    Optional<Product> findActiveById(UUID id);
    Optional<Product> findBySkuAndDeletionMarkFalse(String sku);
}
```

Business logic should use active finders. The inherited `findAll()`, `findById()`, and `findByCode()`
can return deletion-marked rows because restore/admin and reference resolution need tombstones.

## Catalog Or Enum

Use `@Catalog` for warehouses, customers, suppliers, employees, vehicles, cost centers, and products:
users add, merge, deactivate, and edit these records.

Use `@Enumeration` for statuses, payment methods, priority, order source, shipment type, or any
closed set where code branches on constants.

If the set starts closed but the business says "admins may add more next quarter", model it as a
catalog now. Turning an enum into a catalog later is a data migration.
