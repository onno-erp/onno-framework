package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Marshals {@link LocalDate} as {@code AAAA-MM-DD}, the format required by the MIR XSD. */
public class LocalDateXmlAdapter extends XmlAdapter<String, LocalDate> {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public LocalDate unmarshal(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value, FORMAT);
    }

    @Override
    public String marshal(LocalDate value) {
        return value == null ? null : value.format(FORMAT);
    }
}
