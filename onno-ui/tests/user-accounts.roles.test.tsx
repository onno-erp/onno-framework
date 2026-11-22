import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";

// The role hints offer the roles the app declares plus the ones accounts hold, normalized: an
// account stored before roles were upper-cased ("manager") must not offer MANAGER twice.
vi.mock("@/lib/model-store", () => ({
  useModel: () => ({ roles: ["MANAGER", "IT"] }),
}));

const { UserAccounts } = await import("@/components/auth/user-accounts");

const account = (username: string, roles: string[]) => ({
  id: username, username, roles, enabled: true, passwordChangeRequired: false,
  lastLoginAt: null, createdAt: "2026-01-01T00:00:00Z", passwordChangedAt: "2026-01-01T00:00:00Z",
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("UserAccounts role hints", () => {
  it("offers the declared roles and the held ones once each, normalized", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(
      JSON.stringify([account("legacy", ["manager"]), account("ops", ["ROLE_auditor"])]),
      { headers: { "Content-Type": "application/json" } })));

    render(<UserAccounts />);
    fireEvent.click(await screen.findByRole("button", { name: "legacy" }));

    const dialog = await screen.findByRole("dialog");
    const hints = within(dialog).getAllByRole("button").map((b) => b.textContent?.trim() ?? "")
      .filter((t) => t && t !== "Cancel" && t !== "Save");
    expect(hints).toEqual(["AUDITOR", "IT", "MANAGER"]);
  });
});
