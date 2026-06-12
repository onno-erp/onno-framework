package com.onec.performance;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("com.onec.Operation")
@Label("onec Operation")
@Category({"onec", "Framework"})
@StackTrace(false)
public class OnecOperationEvent extends Event {

    @Label("Operation")
    public String operation;

    @Label("Item Count")
    public long itemCount;

    public OnecOperationEvent(String operation, long itemCount) {
        this.operation = operation;
        this.itemCount = itemCount;
    }
}
