import { describe, expect, it } from "vitest";
import { viewportFor } from "@/views/app-shell";

describe("viewportFor", () => {
  it("keeps the desktop shell on a scaled or zoomed laptop", () => {
    // 1366px at 150% display scaling, and a 1920px screen at 200%.
    expect(viewportFor(911, false)).toBe("desktop");
    expect(viewportFor(960, false)).toBe("desktop");
    expect(viewportFor(720, false)).toBe("desktop");
  });

  it("gives touch devices the tablet shell below 1024px", () => {
    expect(viewportFor(911, true)).toBe("tablet");
    expect(viewportFor(1023, true)).toBe("tablet");
    expect(viewportFor(1024, true)).toBe("desktop");
  });

  it("narrows to the bottom bar whatever the pointer", () => {
    expect(viewportFor(719, false)).toBe("tablet");
    expect(viewportFor(599, false)).toBe("mobile");
    expect(viewportFor(599, true)).toBe("mobile");
  });
});
