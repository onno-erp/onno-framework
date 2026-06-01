package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Marshals {@link LocalDateTime} as {@code AAAA-MM-DDThh:mm:ss}, required for entry timestamps. */
public class LocalDateTimeXmlAdapter extends XmlAdapter<String, LocalDateTime> {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public LocalDateTime unmarshal(String value) {
        return (value == null || value.isBlank()) ? null : LocalDateTime.parse(value, FORMAT);
    }

    @Override
    public String marshal(LocalDateTime value) {
        return value == null ? null : value.format(FORMAT);
    }
}
