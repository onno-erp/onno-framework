import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// One failed request must produce exactly one toast (#505): either the api layer's or the
// caller's, never both. These tests use the real api.ts and stub only fetch and the toast sink.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

import { api, ApiError, setUnauthorizedHandler } from "@/lib/api";
import { notifyError } from "@/lib/notify-error";

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

/** Every fetch answers `status` with `body` (a fresh Response each time — a body reads once). */
function failWith(status: number, body: unknown) {
  const fetchMock = vi.fn(async () => response(status, body));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

async function rejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (e) {
    return e;
  }
  throw new Error("expected the call to reject");
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("api layer error toast", () => {
  it("toasts once by default and marks the error as notified", async () => {
    failWith(500, { message: "boom" });

    const error = await rejection(api.saveSettings({ CompanyName: "Acme" }));

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).notified).toBe(true);
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("boom");

    // A caller that reports the same failure again adds nothing.
    notifyError(error, { format: (m) => `Couldn't save: ${m}` });
    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  it("leaves a silent call's toast to the caller, which shows exactly one with its context", async () => {
    failWith(500, { message: "boom" });

    const error = await rejection(api.saveSettings({ CompanyName: "Acme" }, { silent: true }));

    expect((error as ApiError).notified).toBe(false);
    expect(toast.error).not.toHaveBeenCalled();

    notifyError(error, { format: (m) => `Couldn't save: ${m}` });
    notifyError(error, { format: (m) => `Couldn't save: ${m}` });
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("Couldn't save: boom");
  });

  describe("an in-flight GET shared by several callers", () => {
    const silentWithContext = () =>
      api.getRelatedList("catalogs", "orders", "o-1", "tags", undefined, { silent: true })
        .catch((e) => notifyError(e, { format: (m) => `Couldn't load Tags: ${m}` }));
    const byDefault = () => api.getRelatedList("catalogs", "orders", "o-1", "tags").catch(() => undefined);

    it("silent caller first: one fetch, one toast — the caller's own", async () => {
      const fetchMock = failWith(500, { message: "list unavailable" });

      await Promise.all([silentWithContext(), byDefault()]);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(toast.error.mock.calls).toEqual([["Couldn't load Tags: list unavailable"]]);
    });

    it("default caller first: one fetch, one toast — the api layer's", async () => {
      const fetchMock = failWith(500, { message: "list unavailable" });

      await Promise.all([byDefault(), silentWithContext()]);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(toast.error.mock.calls).toEqual([["list unavailable"]]);
    });

    it("two default callers: one fetch, one toast", async () => {
      const fetchMock = failWith(500, { message: "list unavailable" });

      await Promise.all([byDefault(), byDefault()]);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(toast.error.mock.calls).toEqual([["list unavailable"]]);
    });
  });

  it("does not toast a validation error with field detail, so a surface without inline fields reports it once", async () => {
    // The shape OnnoValidationExceptionHandler answers with: 400 + fieldErrors.
    failWith(400, { error: "validation", message: "Name is required", fieldErrors: { name: ["Name is required"] } });

    const error = await rejection(api.createCatalogItem("tags", {}));

    expect(toast.error).not.toHaveBeenCalled();
    notifyError(error, { format: (m) => `Couldn't add: ${m}` });
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("Couldn't add: Name is required");
  });

  it("reports a network failure once — fetch rejects before the api layer can toast", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    const error = await rejection(api.claimProcessTask("task-1"));

    expect(toast.error).not.toHaveBeenCalled();
    notifyError(error, { fallback: "Could not claim task" });
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("Failed to fetch");
  });

  describe("401", () => {
    const onUnauthorized = vi.fn();
    beforeEach(() => setUnauthorizedHandler(onUnauthorized));
    afterEach(() => setUnauthorizedHandler(null));

    it("hands a lapsed session to the recovery handler without a toast of its own; the caller reports it once", async () => {
      // JsonAuthenticationEntryPoint's body.
      failWith(401, { error: "unauthenticated" });

      const error = await rejection(api.claimProcessTask("task-1"));

      expect(onUnauthorized).toHaveBeenCalledOnce();
      expect(toast.error).not.toHaveBeenCalled();
      notifyError(error, { fallback: "Could not claim task" });
      expect(toast.error.mock.calls).toEqual([["unauthenticated"]]);
    });

    it("keeps the login endpoint's bad-credentials 401 away from the recovery handler", async () => {
      failWith(401, { error: "unauthenticated" });

      await rejection(api.login("user@example.test", "wrong"));

      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(toast.error).not.toHaveBeenCalled();
    });
  });

  it("leaves typed feedback to its caller, which reports it once", async () => {
    failWith(409, {
      severity: "error",
      presentation: "toast",
      title: "Task already claimed",
      message: "Someone else picked it up.",
    });

    const error = await rejection(api.claimProcessTask("task-1"));

    expect(toast.error).not.toHaveBeenCalled();
    notifyError(error, { fallback: "Could not claim task" });
    notifyError(error, { fallback: "Could not claim task" });
    expect(toast.error.mock.calls).toEqual([["Someone else picked it up."]]);
  });

  it("toasts a non-ApiError failure with the caller's context", () => {
    notifyError(new TypeError("Failed to fetch"), { format: (m) => `Couldn't add: ${m}` });
    notifyError("boom");
    notifyError(undefined, { fallback: "Move failed" });
    // An Error without a message must not become an empty toast.
    notifyError(new Error(""), { fallback: "Move failed" });

    expect(toast.error.mock.calls).toEqual([
      ["Couldn't add: Failed to fetch"],
      ["boom"],
      ["Move failed"],
      ["Move failed"],
    ]);
  });
});
