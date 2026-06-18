package com.example.domain.constants;

import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

/**
 * The trading name printed on bills and emails. A {@code @Constant} is a single global setting — one
 * row, one {@code value} field — not a list. Read at runtime via {@code ConstantManager} and edited
 * on the Settings page (this app names it explicitly in {@code SettingsPage.constants(...)}).
 */
@Constant(name = "CompanyName")
@Getter
@Setter
public class CompanyName {
    private String value;
}
