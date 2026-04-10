# Plan #4: Information Registers, Enumerations, Constants

## Context

Fills in the remaining 1C metadata types that don't involve posting but are essential for real applications.

## Scope

### Information Registers
- `@InformationRegister` annotation — `name`, `Periodicity` (NONE / DAY / MONTH / QUARTER / YEAR)
- `InformationRecord` base class
- `InformationRegisterManager<T>` — `write()`, `getSliceLast()`, `getSliceFirst()`, `getRecords()`
- Schema generation for information register tables (dimensions + resources + period)
- Slice queries: "latest value for each dimension combination as of date X"

### Enumerations
- `@Enumeration` annotation
- Maps Java enums to a reference table with stable UUIDs
- Can be used as `@Attribute` types on catalogs/documents

### Constants
- `@Constant` annotation
- `ConstantManager` — `get(Class)`, `set(Class, value)`
- Single-row storage table

## Example

```java
@InformationRegister(name = "Prices", periodicity = Periodicity.DAY)
public class PriceRegister extends InformationRecord {
    @Dimension private Ref<Product> product;
    @Dimension private Ref<Warehouse> warehouse;
    @Resource private BigDecimal price;
}
```

## Verification
1. Write information register records, query slices by date
2. Enumerate values persist and can be referenced from catalog attributes
3. Constants read/write correctly
