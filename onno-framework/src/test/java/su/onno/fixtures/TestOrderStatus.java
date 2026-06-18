package su.onno.fixtures;

import su.onno.annotations.Enumeration;

@Enumeration(name = "OrderStatuses")
public enum TestOrderStatus {
    NEW, IN_PROGRESS, COMPLETED
}
