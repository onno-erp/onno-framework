import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useConversationRead } from "../../../../../onno-crm-starter/src/main/widgets/useConversationRead";
let visible = true;
let intersect: () => void;
vi.stubGlobal("IntersectionObserver", class {
  constructor(callback: (entries: { isIntersecting: boolean }[]) => void) { intersect = () => callback([{ isIntersecting: true }]); }
  observe() {} disconnect() {}
});
const element = document.createElement("div");
const viewport = { current: element };
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function setup() {
  visible = true;
  vi.spyOn(document, "visibilityState", "get").mockImplementation(() => visible ? "visible" : "hidden");
  vi.spyOn(element, "getClientRects").mockReturnValue([{}] as DOMRect[] as unknown as DOMRectList);
}
it("acknowledges a new live message without another conversation click and deduplicates events", async () => {
  setup();
  const read = vi.fn().mockResolvedValue({});
  const { rerender } = renderHook(({ revision }) => useConversationRead(viewport, revision, read), { initialProps: { revision: "chat:one" } });
  await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
  rerender({ revision: "chat:two" });
  await waitFor(() => expect(read).toHaveBeenCalledTimes(2));
  act(() => { window.dispatchEvent(new Event("focus")); intersect(); });
  expect(read).toHaveBeenCalledTimes(2);
});
it("waits for the browser and workspace to be visible", async () => {
  setup(); visible = false;
  const read = vi.fn().mockResolvedValue({});
  renderHook(() => useConversationRead(viewport, "chat:one", read));
  expect(read).not.toHaveBeenCalled();
  visible = true;
  vi.mocked(element.getClientRects).mockReturnValue([] as unknown as DOMRectList);
  act(() => document.dispatchEvent(new Event("visibilitychange")));
  expect(read).not.toHaveBeenCalled();
  vi.mocked(element.getClientRects).mockReturnValue([{}] as unknown as DOMRectList);
  act(() => intersect());
  await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
});
it("does not acknowledge unloaded conversations and retries failures on focus", async () => {
  setup();
  const read = vi.fn().mockRejectedValueOnce(Error("offline")).mockResolvedValue({});
  const { rerender } = renderHook(({ revision }: { revision: string | null }) => useConversationRead(viewport, revision, read), { initialProps: { revision: null as string | null } });
  expect(read).not.toHaveBeenCalled();
  rerender({ revision: "chat:one" });
  await act(async () => {});
  act(() => window.dispatchEvent(new Event("focus")));
  await waitFor(() => expect(read).toHaveBeenCalledTimes(2));
});
