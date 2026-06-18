package su.onno.repository;

import su.onno.model.AccumulationRecord;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@NoRepositoryBean
public interface RegisterRepository<T extends AccumulationRecord> extends Repository<T, UUID> {

    List<T> getBalance();

    List<T> getBalance(Map<String, Object> filters);

    List<T> getBalance(Consumer<RegisterFilter<T>> filter);

    List<T> getTurnover(LocalDateTime from, LocalDateTime to);

    List<T> getTurnover(LocalDateTime from, LocalDateTime to, Map<String, Object> filters);

    List<T> getTurnover(LocalDateTime from, LocalDateTime to, Consumer<RegisterFilter<T>> filter);

    List<T> getRecordsByDocument(UUID documentRef);

    T addReceipt();

    T addExpense();

    T addReceipt(Consumer<T> configurator);

    T addExpense(Consumer<T> configurator);

    RegisterQueryBuilder<T> query();

    void rebuildTotals();

    boolean verifyTotals();
}
