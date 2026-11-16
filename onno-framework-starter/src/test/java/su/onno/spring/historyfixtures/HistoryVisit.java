package su.onno.spring.historyfixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** A document that opted into record history, saved through a typed repository. */
@Document(name = "HistoryVisits", autoNumber = false, history = true)
@Getter
@Setter
public class HistoryVisit extends DocumentObject {

    @Attribute(length = 200)
    private String complaint;

    @Attribute(secret = true)
    private String accessCode;

    @TabularSection(name = "procedures")
    private List<HistoryVisitLine> procedures = new ArrayList<>();
}
