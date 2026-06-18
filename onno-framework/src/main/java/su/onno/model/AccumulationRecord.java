package su.onno.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class AccumulationRecord implements Persistable<UUID> {

    @Id
    private UUID id;
    private LocalDateTime period;
    private boolean active;
    private UUID documentRef;
    private MovementType movementType;

    @Transient
    @JsonIgnore
    private boolean isNew = true;

    protected AccumulationRecord() {
        this.active = true;
        this.movementType = MovementType.RECEIPT;
    }

    @Override
    @JsonIgnore
    public boolean isNew() {
        return isNew;
    }
}
