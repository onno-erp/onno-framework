package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.AfterWriteHandler;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Document(name = "TestInvoices", numberLength = 11)
@Getter
@Setter
public class TestInvoice extends DocumentObject implements BeforeWriteHandler, AfterWriteHandler {

    @Attribute(length = 200)
    private String counterparty;

    @TabularSection(name = "items")
    private List<TestInvoiceLine> items = new ArrayList<>();

    private boolean beforeWriteCalled;
    private boolean afterWriteCalled;

    @Override
    public void beforeWrite() {
        this.beforeWriteCalled = true;
    }

    @Override
    public void afterWrite() {
        this.afterWriteCalled = true;
    }
}
