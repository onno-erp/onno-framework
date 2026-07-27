import { describe, expect, it } from "vitest";
import {
  getUiFeatureCustomComponents,
  handleUiFeatureAction,
  registerUiFeature,
} from "@/lib/ui-feature-host";

describe("UI feature host", () => {
  it("registers custom DivKit blocks and actions through one feature contract", () => {
    registerUiFeature({
      id: "test-feature",
      customComponents: {
        "onno-test-feature": () => null,
      },
      handleAction: (url) => url === "onno://test-feature",
    });

    expect(getUiFeatureCustomComponents().get("onno-test-feature")).toEqual({
      element: "onno-test-feature",
    });
    expect(handleUiFeatureAction("onno://test-feature")).toBe(true);
    expect(handleUiFeatureAction("onno://other")).toBe(false);
  });
});
