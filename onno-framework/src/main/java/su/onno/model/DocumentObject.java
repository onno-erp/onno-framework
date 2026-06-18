package su.onno.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@ToString
public abstract class DocumentObject implements Persistable<UUID> {

    @Id
    private UUID id;
    private String number;
    private LocalDateTime date;
    private boolean posted;
    private boolean deletionMark;

    @Version
    private Integer version;

    @Transient
    @JsonIgnore
    private boolean isNew = true;

    @Override
    @JsonIgnore
    public boolean isNew() {
        return isNew;
    }
}
