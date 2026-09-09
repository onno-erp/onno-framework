import { afterEach, expect, it, vi } from "vitest";
const error = vi.hoisted(() => vi.fn());
vi.mock("@/components/ui/toast", () => ({ toast: { error } }));
import { api } from "@/lib/api";
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });
it("rejects unavailable presence without displaying a misleading record-access toast", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 403, statusText: "Forbidden", text: async () => JSON.stringify({message:"Current user is not allowed to read catalog: crm_conversations"}) }));
  await expect(api.presence("/catalogs/crm_conversations/one", "enter")).rejects.toMatchObject({status:403});
  expect(error).not.toHaveBeenCalled();
});
