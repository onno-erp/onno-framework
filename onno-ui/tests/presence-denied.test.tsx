import { afterEach, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
const mocks = vi.hoisted(() => ({ presence: vi.fn(), leavePresence: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: mocks }));
import { usePanePresence } from "@/lib/presence-store";
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });
it("stops optional presence polling when a scoped record denies catalog-wide presence", async () => {
  vi.useFakeTimers();
  mocks.presence.mockRejectedValue({ status: 403 });
  const view = renderHook(() => usePanePresence("/catalogs/crm_conversations/one"));
  await act(async () => { await Promise.resolve(); });
  await act(async () => { await vi.advanceTimersByTimeAsync(45_000); });
  expect(mocks.presence).toHaveBeenCalledTimes(1);
  view.unmount();
  expect(mocks.leavePresence).not.toHaveBeenCalled();
});
