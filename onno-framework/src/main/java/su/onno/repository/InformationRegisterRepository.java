package su.onno.repository;

import su.onno.model.InformationRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface InformationRegisterRepository<T extends InformationRecord> {

    void write(T record);

    List<T> getSliceLast(LocalDateTime date);

    List<T> getSliceLast(LocalDateTime date, Map<String, Object> filters);

    List<T> getSliceFirst(LocalDateTime date);

    List<T> getSliceFirst(LocalDateTime date, Map<String, Object> filters);

    List<T> getRecords();

    List<T> getRecords(Map<String, Object> filters);

    void delete(T record);
}
