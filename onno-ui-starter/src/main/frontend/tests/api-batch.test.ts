import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import type { ActionFeedback, BatchResult } from "@/lib/types";

function jsonResponse(body: BatchResult) {
  return {
    ok: true,
    status: 200,
    statusText: "OK",
    text: async () => JSON.stringify(body),
  };
}

describe("large UI batches", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("chunks an action above 500 ids and combines every tally and first feedback", async () => {
    const feedback: ActionFeedback = {
      severity: "warning",
      presentation: "toast",
      message: "One row needs review",
    };
    const responses: BatchResult[] = [
      { ok: 500, failed: [], total: 500 },
      {
        ok: 498,
        failed: ["id-501", "id-502"],
        total: 500,
        feedback,
        feedbackRejected: true,
      },
      { ok: 201, failed: [], total: 201 },
    ];
    const fetchMock = vi.fn(async () => jsonResponse(responses[fetchMock.mock.calls.length - 1]));
    vi.stubGlobal("fetch", fetchMock);
    const ids = Array.from({ length: 1_201 }, (_, index) => `id-${index}`);

    const result = await api.runActionBatch("documents", "orders", "advance", ids, {
      reason: "quarter close",
    });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.map(([, init]) => JSON.parse(String(init?.body)).ids.length))
      .toEqual([500, 500, 201]);
    expect(fetchMock.mock.calls.map(([, init]) => JSON.parse(String(init?.body)).inputs))
      .toEqual([
        { reason: "quarter close" },
        { reason: "quarter close" },
        { reason: "quarter close" },
      ]);
    expect(result).toEqual({
      ok: 1_199,
      failed: ["id-501", "id-502"],
      total: 1_201,
      feedback,
      feedbackRejected: true,
    });
  });

  it("uses the same 500-id chunks for batch delete", async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      const size = JSON.parse(String(init?.body)).ids.length;
      return jsonResponse({ ok: size, failed: [], total: size });
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await api.batchDelete(
      "catalogs",
      "books",
      Array.from({ length: 501 }, (_, index) => `id-${index}`)
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map(([, init]) => JSON.parse(String(init?.body)).ids.length))
      .toEqual([500, 1]);
    expect(result).toEqual({ ok: 501, failed: [], total: 501 });
  });
});
