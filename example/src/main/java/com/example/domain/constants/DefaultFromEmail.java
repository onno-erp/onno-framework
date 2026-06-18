package com.example.domain.constants;

import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

/**
 * Default "from" address for outgoing mail (e.g. the booking-confirmed template), a single global
 * {@code @Constant} (see {@link CompanyName} for the concept). Mirrors {@code onno.mail.default-from}
 * in {@code application.yaml} but is editable at runtime on the Settings page.
 */
@Constant(name = "DefaultFromEmail")
@Getter
@Setter
public class DefaultFromEmail {
    private String value;
}
