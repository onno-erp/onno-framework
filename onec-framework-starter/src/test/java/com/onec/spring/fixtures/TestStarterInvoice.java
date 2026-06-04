package com.onec.spring.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.model.DocumentObject;

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
