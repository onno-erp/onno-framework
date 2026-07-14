# Registers Examples

## Table Of Contents

- Balance Register
- Turnover Register
- Information Register
- Querying Accumulation Registers
- Querying Information Registers
- Register UI Hints

## Balance Register

```java
@AccumulationRegister(name = "Stock", title = "Stock", type = AccumulationType.BALANCE,
        context = "Inventory")
@AccessControl(readRoles = {"WAREHOUSE", "ADMIN"})
@Getter
@Setter
public class StockRegister extends AccumulationRecord {

    @Dimension(displayName = "Warehouse")
    private Ref<Warehouse> warehouse;

    @Dimension(displayName = "Product")
    private Ref<Product> product;

    @Resource(displayName = "Quantity", precision = 15, scale = 3)
    private BigDecimal quantity;
}
```

A balance register rejects posting movements that would make the resulting balance negative.
Use this for stock, cash, reservations, loyalty points, and open obligations.

## Turnover Register

```java
@AccumulationRegister(name = "Sales", title = "Sales", type = AccumulationType.TURNOVER,
        context = "Sales")
@AccessControl(readRoles = {"SALES", "ADMIN"})
@Getter
@Setter
public class SalesRegister extends AccumulationRecord {

    @Dimension(displayName = "Product")
    private Ref<Product> product;

    @Dimension(displayName = "Salesperson")
    private Ref<Employee> salesperson;

    @Resource(displayName = "Quantity", precision = 15, scale = 3)
    private BigDecimal quantity;

    @Resource(displayName = "Revenue", precision = 15, scale = 2)
    private BigDecimal revenue;
}
```

Use turnover when current balance is not meaningful. Revenue this month and hours logged this week
are turnover, not balance.

## Information Register

```java
@InformationRegister(name = "Prices", periodicity = Periodicity.DAY, context = "Catalog")
@AccessControl(readRoles = {"SALES", "ADMIN"}, writeRoles = {"ADMIN"})
@Getter
@Setter
public class PriceRegister extends InformationRecord {

    @Dimension(displayName = "Product")
    private Ref<Product> product;

    @Dimension(displayName = "Price type")
    private Ref<PriceType> priceType;

    @Resource(displayName = "Price", precision = 15, scale = 2)
    private BigDecimal price;
}
```

Use information registers for "what was true as of date X?" facts: prices, exchange rates, employee
rates, SLA settings, supplier lead times, and warehouse-specific configuration.

## Querying Accumulation Registers

```java
@Service
public class StockService {
    private final RegisterRepository<StockRegister> stock;

    public StockService(RegisterRepository<StockRegister> stock) {
        this.stock = stock;
    }

    public BigDecimal onHand(Ref<Warehouse> warehouse, Ref<Product> product) {
        var rows = stock.getBalance(Map.of(
                "warehouse", warehouse.id(),
                "product", product.id()));
        return rows.stream()
                .map(row -> row.getBigDecimal("quantity"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

For posting code that checks many dimension tuples, prefer query filters over loading the whole
register slice. The fluent query API supports `whereIn(field, values)` and tuple filters.

## Querying Information Registers

```java
@Service
public class PriceService {
    private final InformationRegisterRepository<PriceRegister> prices;

    public PriceService(InformationRegisterRepository<PriceRegister> prices) {
        this.prices = prices;
    }

    public Optional<PriceRegister> priceAt(Ref<Product> product, LocalDateTime at) {
        return prices.getSliceLast(at, Map.of("product", product.id())).stream().findFirst();
    }
}
```

`getSliceLast` answers the latest record at or before the date. `getSliceFirst` answers the earliest
record at or after the date.

## Register UI Hints

Registers can have `EntityView` hints even though they are report/read surfaces:

```java
@Component
public class SalesRegisterView implements EntityView {
    @Override
    public Class<?> entity() {
        return SalesRegister.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("period", "product", "salesperson", "quantity", "revenue")
                .label("revenue", "Revenue")
                .sortBy("period", true);
        list.filter("period").label("Period").dateRange();
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("period").format("dd-MM-yyyy")
            .field("revenue").format("currency:USD");
    }
}
```
