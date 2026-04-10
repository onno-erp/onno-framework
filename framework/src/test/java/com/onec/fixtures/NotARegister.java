package com.onec.fixtures;

import com.onec.annotations.AccumulationRegister;

@AccumulationRegister(name = "Bad")
public class NotARegister {
    // Does not extend AccumulationRecord — should fail scanning
}
