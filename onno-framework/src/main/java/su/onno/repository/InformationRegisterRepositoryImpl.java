package su.onno.repository;

import su.onno.model.InformationRecord;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class InformationRegisterRepositoryImpl<T extends InformationRecord>
        implements InformationRegisterRepository<T> {

    private final InformationRegisterPersistence<T> persistence;

    public InformationRegisterRepositoryImpl(InformationRegisterPersistence<T> persistence) {
        this.persistence = persistence;
    }

    @Override
    public void write(T record) {
        persistence.write(record);
    }

    @Override
    public List<T> getSliceLast(LocalDateTime date) {
        return persistence.getSliceLast(date, Collections.emptyMap());
    }

    @Override
    public List<T> getSliceLast(LocalDateTime date, Map<String, Object> filters) {
        return persistence.getSliceLast(date, filters);
    }

    @Override
    public List<T> getSliceFirst(LocalDateTime date) {
        return persistence.getSliceFirst(date, Collections.emptyMap());
    }

    @Override
    public List<T> getSliceFirst(LocalDateTime date, Map<String, Object> filters) {
        return persistence.getSliceFirst(date, filters);
    }

    @Override
    public List<T> getRecords() {
        return persistence.getRecords(Collections.emptyMap());
    }

    @Override
    public List<T> getRecords(Map<String, Object> filters) {
        return persistence.getRecords(filters);
    }

    @Override
    public void delete(T record) {
        persistence.delete(record);
    }
}
