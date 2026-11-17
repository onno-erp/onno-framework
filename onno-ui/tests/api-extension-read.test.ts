import { afterEach, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
afterEach(() => vi.unstubAllGlobals());
it("shares overlapping extension reads but fetches again after completion", async () => {
  let resolve!: (response: Response) => void;
  const pending = new Promise<Response>(done => {resolve=done;});
  const fetcher = vi.fn().mockReturnValueOnce(pending).mockResolvedValue(new Response('{"version":2}'));
  vi.stubGlobal("fetch", fetcher);
  const first=api.getJson("/api/extension/example"), second=api.getJson("/api/extension/example");
  expect(fetcher).toHaveBeenCalledTimes(1);
  resolve(new Response('{"version":1}'));
  expect(await first).toEqual({version:1});
  expect(await second).toEqual({version:1});
  expect(await api.getJson("/api/extension/example")).toEqual({version:2});
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it("does not send extension reads outside the local API", async () => {
  const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher);
  await expect(api.getJson("https://example.com/api/data")).rejects.toThrow("same-origin");
  expect(fetcher).not.toHaveBeenCalled();
});
