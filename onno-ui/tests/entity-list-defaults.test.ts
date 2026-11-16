import { describe, expect, it } from "vitest";
import {
  initialFilterState,
  normalizeNumberBound,
  numberRangeLabel,
  type InitialFilterControl,
} from "../src/components/lists/list-defaults";

const filters: InitialFilterControl[] = [
  {
    key: "assignedTo",
    type: "multiOptions",
  },
  {
    key: "status",
    type: "options",
  },
];

describe("embedded list defaults", () => {
  it("seeds removable option filter state", () => {
    expect(initialFilterState(filters, {
      assignedTo: ["employee-1"],
      status: ["READY"],
    })).toEqual({
      assignedTo: { in: ["employee-1"] },
      status: { eq: "READY" },
    });
  });

  it("leaves undeclared defaults clear", () => {
    expect(initialFilterState(filters)).toEqual({
      assignedTo: {},
      status: {},
    });
  });
});

describe("number range filter", () => {
  it("seeds a default from/to range and tolerates a single lower bound", () => {
    const range: InitialFilterControl[] = [{ key: "total", type: "numberRange" }];
    expect(initialFilterState(range, { total: ["100", "500,5"] })).toEqual({
      total: { from: "100", to: "500.5" },
    });
    expect(initialFilterState(range, { total: ["100"] })).toEqual({ total: { from: "100", to: "" } });
    expect(initialFilterState(range, { total: ["abc"] })).toEqual({ total: {} });
  });

  it("normalizes a typed bound to a plain dot decimal", () => {
    expect(normalizeNumberBound(" 1 250,5 ")).toBe("1250.5");
    expect(normalizeNumberBound("-3.")).toBe("-3");
    expect(normalizeNumberBound(".5")).toBe(".5");
    expect(normalizeNumberBound("-")).toBe("");
    expect(normalizeNumberBound("1e5")).toBe("");
    expect(normalizeNumberBound("12abc")).toBe("");
  });

  it("labels open-ended ranges", () => {
    expect(numberRangeLabel("100", "500")).toBe("100 – 500");
    expect(numberRangeLabel("100", "")).toBe("≥ 100");
    expect(numberRangeLabel("", "500")).toBe("≤ 500");
  });
});
