package su.onno.spring.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Document(name = "StarterInvoices", numberLength = 11, autoNumber = false)
@Getter
@Setter
public class TestStarterInvoice extends DocumentObject {

    @Attribute(length = 200)
    private String counterparty;

    @TabularSection(name = "items")
    private List<TestStarterLine> items = new ArrayList<>();
}
