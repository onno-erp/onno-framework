package su.onno.performance;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("su.onno.Operation")
@Label("onno Operation")
@Category({"onno", "Framework"})
@StackTrace(false)
public class OnnoOperationEvent extends Event {

    @Label("Operation")
    public String operation;

    @Label("Item Count")
    public long itemCount;

    public OnnoOperationEvent(String operation, long itemCount) {
        this.operation = operation;
        this.itemCount = itemCount;
    }
}
