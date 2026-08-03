# Registers Examples

## Table Of Contents

- Balance Register
- Turnover Register
- Information Register
- Querying Accumulation Registers
- Querying Information Registers
- Register UI Hints
- Runtime Verification

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
Dimensions may also use an `@Enumeration` enum. Set the enum constant on the movement; onno stores
its stable UUID in movement and totals tables and maps it back for typed filters and reads.
Declare `allowNegative = true` only for debt/overdraft balances. The guard applies only to BALANCE
and checks all resources atomically during posting. Use `postingOrder = CHRONOLOGICAL` when
backdated changes must preserve order-dependent historical balances.

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
        var rows = stock.getBalance(f -> f
                .where(StockRegister::getWarehouse, warehouse)
                .where(StockRegister::getProduct, product));
        return rows.stream()
                .map(StockRegister::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

For posting code that checks many dimension tuples, prefer query filters over loading the whole
register slice. The fluent query API supports `whereIn(field, values)` and tuple filters:

```java
stock.query().balance()
        .whereIn(StockRegister::getWarehouse, StockRegister::getProduct,
                List.of(List.of(warehouseA, productA), List.of(warehouseB, productB)))
        .execute();
```

Pass `Ref<T>` and enum constants directly to typed filters. Storage uses UUIDs; typed results
reconstruct the declared Ref target and enum constant.

## Querying Information Registers

```java
@Service
public class PriceService {
    private final InformationRegisterRepository<PriceRegister> prices;

    public PriceService(InformationRegisterRepository<PriceRegister> prices) {
        this.prices = prices;
    }

    public Optional<PriceRegister> priceAt(Ref<Product> product, LocalDateTime at) {
        return prices.getSliceLast(at, Map.of("product", product)).stream().findFirst();
    }
}
```

`getSliceLast` answers the latest record at or before the date. `getSliceFirst` answers the earliest
record at or after the date.

## Register UI Hints

Registers can have `EntityView` hints even though they are report/read surfaces:

```java
@Component
public class SalesRegisterView implements EntityView<SalesRegister> {
    @Override
    public Class<SalesRegister> entity() {
        return SalesRegister.class;
    }

    @Override
    public void list(ListSpec<SalesRegister> list) {
        list.columns(SalesRegister::getPeriod, SalesRegister::getProduct,
                SalesRegister::getSalesperson, SalesRegister::getQuantity,
                SalesRegister::getRevenue)
            .label(SalesRegister::getRevenue, "Revenue")
            .sortBy(SalesRegister::getPeriod, true);
        list.filter(SalesRegister::getPeriod).label("Period").dateRange();
    }

    @Override
    public void fields(EntityConfigBuilder<SalesRegister> f) {
        f.field(SalesRegister::getPeriod).format("dd-MM-yyyy");
        f.field(SalesRegister::getRevenue).format("currency:USD");
    }
}
```

## Runtime Verification

After authentication, accumulation REST reads use logical register names:

```bash
curl -fsS -b "$jar" "$base/api/registers/Stock/balance?product=$product_id" | jq -e .
curl -fsS -b "$jar" \
  "$base/api/registers/Sales/turnover?from=2026-08-01T00:00:00&to=2026-09-01T00:00:00" | jq -e .
curl -fsS -b "$jar" "$base/api/registers/Stock/movements" | jq -e .
```

There is no generic information-register REST CRUD/read endpoint. Verify `PriceRegister` through
`InformationRegisterRepository`, a focused integration test, or an application-owned API.
