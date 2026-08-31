import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const { useRecordViewers } = vi.hoisted(() => ({ useRecordViewers: vi.fn() }));
vi.mock("@/lib/presence-store", () => ({
  useRecordViewers,
  usePanePresence: vi.fn(),
}));

import { TabPresence } from "@/components/presence-surfaces";

describe("TabPresence", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("shows an unlinked authenticated user from their username fallback", () => {
    useRecordViewers.mockReturnValue([
      {
        userId: "admin@onnobooks.local",
        displayName: "admin@onnobooks.local",
      },
    ]);

    render(<TabPresence path="/documents/Orders/order-17" />);

    expect(useRecordViewers).toHaveBeenCalledWith("order-17");
    expect(screen.getByRole("status", { name: "1 person viewing" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "admin@onnobooks.local" })).toBeInTheDocument();
  });

  it("does not attach record presence to an entity-list tab", () => {
    useRecordViewers.mockReturnValue([]);

    const { container } = render(<TabPresence path="/documents/Orders" />);

    expect(useRecordViewers).toHaveBeenCalledWith(null);
    expect(container).toBeEmptyDOMElement();
  });
});
