package su.onno.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class InformationRecord {

    private UUID id;
    private LocalDateTime period;
}
