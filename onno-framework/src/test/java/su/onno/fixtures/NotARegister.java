package su.onno.fixtures;

import su.onno.annotations.AccumulationRegister;

@AccumulationRegister(name = "Bad")
public class NotARegister {
    // Does not extend AccumulationRecord — should fail scanning
}
