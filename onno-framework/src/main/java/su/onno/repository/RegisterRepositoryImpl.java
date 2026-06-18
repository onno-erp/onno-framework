package su.onno.repository;

import su.onno.model.AccumulationRecord;
import su.onno.model.MovementType;
import su.onno.posting.RegisterPersistence;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class RegisterRepositoryImpl<T extends AccumulationRecord> implements RegisterRepository<T> {

    private final RegisterPersistence<T> persistence;
    private final Class<T> registerClass;
    private final List<T> pendingMovements = new ArrayList<>();

    public RegisterRepositoryImpl(RegisterPersistence<T> persistence, Class<T> registerClass) {
        this.persistence = persistence;
        this.registerClass = registerClass;
    }

    @Override
    public List<T> getBalance() {
        return persistence.getBalanceTyped(null);
    }

    @Override
    public List<T> getBalance(Map<String, Object> filters) {
        return persistence.getBalanceTyped(filters);
    }

    @Override
    public List<T> getBalance(Consumer<RegisterFilter<T>> filter) {
        return persistence.getBalanceTyped(resolveFilter(filter));
    }

    @Override
    public List<T> getTurnover(LocalDateTime from, LocalDateTime to) {
        return persistence.getTurnoverTyped(from, to, null);
    }

    @Override
    public List<T> getTurnover(LocalDateTime from, LocalDateTime to, Map<String, Object> filters) {
        return persistence.getTurnoverTyped(from, to, filters);
    }

    @Override
    public List<T> getTurnover(LocalDateTime from, LocalDateTime to, Consumer<RegisterFilter<T>> filter) {
        return persistence.getTurnoverTyped(from, to, resolveFilter(filter));
    }

    @Override
    public List<T> getRecordsByDocument(UUID documentRef) {
        return persistence.getRecordsByDocument(documentRef);
    }

    @Override
    public T addReceipt() {
        return createRecord(MovementType.RECEIPT);
    }

    @Override
    public T addExpense() {
        return createRecord(MovementType.EXPENSE);
    }

    @Override
    public T addReceipt(Consumer<T> configurator) {
        T record = addReceipt();
        configurator.accept(record);
        return record;
    }

    @Override
    public T addExpense(Consumer<T> configurator) {
        T record = addExpense();
        configurator.accept(record);
        return record;
    }

    @Override
    public RegisterQueryBuilder<T> query() {
        return new RegisterQueryBuilder<>(persistence);
    }

    @Override
    public void rebuildTotals() {
        persistence.rebuildTotals();
    }

    @Override
    public boolean verifyTotals() {
        return persistence.verifyTotals();
    }

    public List<T> getPendingMovements() {
        return Collections.unmodifiableList(pendingMovements);
    }

    public boolean hasPendingMovements() {
        return !pendingMovements.isEmpty();
    }

    public void clearPending() {
        pendingMovements.clear();
    }

    public RegisterPersistence<T> getPersistence() {
        return persistence;
    }

    private Map<String, Object> resolveFilter(Consumer<RegisterFilter<T>> filter) {
        RegisterFilter<T> builder = new RegisterFilter<>();
        filter.accept(builder);
        return persistence.resolveFieldFilters(builder.getFieldFilters());
    }

    private T createRecord(MovementType movementType) {
        try {
            T record = registerClass.getDeclaredConstructor().newInstance();
            record.setMovementType(movementType);
            pendingMovements.add(record);
            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create register record instance", e);
        }
    }
}
