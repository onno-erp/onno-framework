package com.example.domain.constants;

import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

/**
 * Whether the automatic maintenance procedure ({@link com.example.jobs.AutoArchiveJob}) runs.
 * Surfaced on the Settings page as an on/off switch; the background job checks it on every tick.
 */
@Constant(name = "AutoArchiveEnabled")
@Getter
@Setter
public class AutoArchiveEnabled {
    private boolean value;
}
