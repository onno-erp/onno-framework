import { afterEach, describe, expect, it, vi } from "vitest";
import { openRoute } from "@/lib/navigation";

afterEach(() => vi.restoreAllMocks());

describe("workspace navigation", () => {
  it.each([
    "onno://catalogs/customers/c-1",
    "onno://theme/toggle",
    "onno://",
    "/",
    "/documentation",
    "/documents/orders/new?customer=c-1#details",
  ])("dispatches %s unchanged to the workspace", (route) => {
    const dispatch = vi.spyOn(window, "dispatchEvent");
    openRoute(route);
    expect(dispatch).toHaveBeenCalledOnce();
    expect(dispatch.mock.calls[0][0]).toMatchObject({ type: "onno:open", detail: route });
  });

  it.each(["", "documents/orders", "https://example.com", "//example.com", "/\\example.com"])(
    "does not interpret %s as an application route", (route) => {
      const dispatch = vi.spyOn(window, "dispatchEvent");
      openRoute(route);
      expect(dispatch).not.toHaveBeenCalled();
    }
  );
});
