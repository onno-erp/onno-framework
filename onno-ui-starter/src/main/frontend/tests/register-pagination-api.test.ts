import { afterEach, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
afterEach(() => vi.unstubAllGlobals());
it("preserves the register envelope and sends the continuation", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ rows: [{ amount: 1 }], nextCursor: "next", hasMore: true }), { status: 200, headers: { "Content-Type": "application/json" } }));
  vi.stubGlobal("fetch", fetcher);
  const page = await api.getBalance("Stock", undefined, { limit: 1, cursor: "previous" });
  expect(page.nextCursor).toBe("next"); expect(page.hasMore).toBe(true);
  expect(String(fetcher.mock.calls[0][0])).toContain("cursor=previous");
  expect(String(fetcher.mock.calls[0][0])).toContain("limit=1");
});
