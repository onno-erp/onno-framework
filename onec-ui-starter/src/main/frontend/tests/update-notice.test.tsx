import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, waitFor } from "@testing-library/react";

// Hoisted so the vi.mock factories below (which run before module init) can close over them.
const { toast, getConfig } = vi.hoisted(() => ({ toast: vi.fn(), getConfig: vi.fn() }));

vi.mock("sonner", () => ({ toast }));
vi.mock("@/lib/api", () => ({ api: { getConfig } }));

import { UpdateNotice } from "@/components/update-notice";

const base = { readOnly: false, basePath: "/ui" };

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

afterEach(() => {
  cleanup();
  toast.mockReset();
  getConfig.mockReset();
  localStorage.clear();
});

describe("UpdateNotice", () => {
  it("raises a toast naming the new version with a release-notes action", async () => {
    getConfig.mockResolvedValue({
      ...base,
      update: { available: true, current: "0.10.0", latest: "0.11.0", url: "https://onno.su/r/0.11.0" },
    });

    render(<UpdateNotice />);

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(toast.mock.calls[0][0]).toContain("0.11.0");
    const opts = toast.mock.calls[0][1];
    expect(opts.action.label).toBe("Release notes");
    expect(opts.cancel.label).toBe("Dismiss");
  });

  it("stays silent when no update is available", async () => {
    getConfig.mockResolvedValue({
      ...base,
      update: { available: false, current: "0.11.0", latest: "0.11.0", url: null },
    });

    render(<UpdateNotice />);
    await flush();

    expect(toast).not.toHaveBeenCalled();
  });

  it("does not re-show a version the user already dismissed", async () => {
    localStorage.setItem("onec.update.dismissed", "0.11.0");
    getConfig.mockResolvedValue({
      ...base,
      update: { available: true, current: "0.10.0", latest: "0.11.0", url: null },
    });

    render(<UpdateNotice />);
    await flush();

    expect(toast).not.toHaveBeenCalled();
  });

  it("ignores a failed config fetch", async () => {
    getConfig.mockRejectedValue(new Error("offline"));

    render(<UpdateNotice />);
    await flush();

    expect(toast).not.toHaveBeenCalled();
  });
});
