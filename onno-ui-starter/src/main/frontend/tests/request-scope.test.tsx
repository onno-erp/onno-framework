import { act, renderHook, cleanup } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { useRequestScope } from "@/hooks/use-request-scope";
afterEach(cleanup);
it("ignores delayed pages after switching records and returning to the same record", () => {
  const { result, rerender } = renderHook(({ id }) => useRequestScope(id), { initialProps: { id: "A" } });
  const old = result.current.capture();
  rerender({ id: "B" }); expect(old()).toBe(false);
  const next = result.current.capture();
  rerender({ id: "A" }); expect(old()).toBe(false); expect(next()).toBe(false);
});
it("replacement reads invalidate pending continuations and unmount invalidates all callbacks", () => {
  const { result, unmount } = renderHook(() => useRequestScope("record"));
  const old = result.current.capture();
  let current!: () => boolean;
  act(() => { current = result.current.begin(); });
  expect(old()).toBe(false); expect(current()).toBe(true);
  unmount(); expect(current()).toBe(false);
});
