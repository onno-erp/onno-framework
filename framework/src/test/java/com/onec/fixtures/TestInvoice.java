package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.lifecycle.AfterWriteHandler;
import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.model.DocumentObject;

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
