import { describe, expect, it } from "vitest";
import { initialFilterState, type InitialFilterControl } from "../src/components/list-defaults";

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
