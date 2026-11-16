import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useEffect } from "react";
import { MemoryRouter } from "react-router";

const { runAction, runPageAction, applyDelta, refreshed } = vi.hoisted(() => ({
  runAction: vi.fn(),
  runPageAction: vi.fn(),
  applyDelta: vi.fn(() => true),
  refreshed: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: { runAction, runPageAction },
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/lib/icon-bridge", () => ({ DynamicLucide: () => null }));
vi.mock("@/components/action-form-dialog", () => ({
  ActionFormDialog: ({ onSubmit }: { onSubmit: (values: Record<string, string>) => Promise<void> }) => (
    <button onClick={() => void onSubmit({ note: "Test" })}>Submit action form</button>
  ),
}));
vi.mock("@/views/divkit-content", async () => {
  const { forwardRef, useImperativeHandle } = await import("react");
  return {
    DivKitContent: forwardRef(function FakeContent({ surfacePath, onAction }: {
      surfacePath: string;
      onAction: (action: { url: string }) => void;
    }, ref) {
      useImperativeHandle(ref, () => ({ applyDelta }));
      useEffect(() => subscribeSurfaceRefresh(surfacePath, () => refreshed(surfacePath)), [surfacePath]);
      return <div data-onno-surface-path={surfacePath}>
        <input aria-label={`Draft ${surfacePath}`} defaultValue="Unsaved draft" />
        <button onClick={() => onAction({ url: "onno://action/documents/orders/advance/o1" })}>
          DivKit action {surfacePath}
        </button>
      </div>;
    }),
  };
});

import { ActionsCluster, type ActionItem } from "@/lib/actions-menu-bridge";
import { PageActionsBar } from "@/components/page-actions-bar";
import { ContentPane } from "@/views/content-pane";
import { clearFormDirty, markFormDirty } from "@/lib/dirty-forms";
import { requestSurfaceRefresh, subscribeSurfaceRefresh, surfaceRefreshCallback } from "@/lib/surface-refresh";

const source = "/documents/orders/o1";
const other = "/catalogs/customers/c1";
const card = { card: { states: [{ div: { items: [{ type: "text", text: "Fresh" }] } }] } };
let fetchMock: ReturnType<typeof vi.fn>;

function pane(path: string, onAction = vi.fn()) {
  return <ContentPane path={path} viewport="desktop" theme="light" profile={null}
    onAction={onAction} registry={new Map()} skeletonBg="white" />;
}

beforeEach(() => {
  runAction.mockReset().mockResolvedValue({ refresh: true });
  runPageAction.mockReset().mockResolvedValue({ refresh: true });
  applyDelta.mockClear().mockReturnValue(true);
  refreshed.mockClear();
  fetchMock = vi.fn().mockImplementation(async () => new Response(JSON.stringify(card), {
    status: 200, headers: { "Content-Type": "application/json" },
  }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  clearFormDirty(source);
  vi.unstubAllGlobals();
});

describe("source-tab refresh without an SSE event (#447)", () => {
  it("signals only the requested surface without fetching or patching its DivKit card", async () => {
    render(<>{pane(source)}{pane(other)}</>);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const draft = screen.getByLabelText(`Draft ${source}`);
    const otherDraft = screen.getByLabelText(`Draft ${other}`);
    fireEvent.change(draft, { target: { value: "My unfinished work" } });

    act(() => requestSurfaceRefresh(source));

    expect(refreshed).toHaveBeenCalledExactlyOnceWith(source);
    expect(applyDelta).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText(`Draft ${source}`)).toBe(draft);
    expect(screen.getByLabelText(`Draft ${other}`)).toBe(otherDraft);
    expect(draft).toHaveValue("My unfinished work");
  });

  it("lets islands refresh even if a sibling form in the surface is dirty", async () => {
    render(pane(source));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    markFormDirty(source);
    act(() => requestSurfaceRefresh(source));
    expect(refreshed).toHaveBeenCalledExactlyOnceWith(source);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(applyDelta).not.toHaveBeenCalled();
  });

  it("passes the originating path with a DivKit header action", async () => {
    const onAction = vi.fn();
    render(pane(source, onAction));
    fireEvent.click(await screen.findByText(`DivKit action ${source}`));
    expect(onAction).toHaveBeenCalledWith({
      url: "onno://action/documents/orders/advance/o1", surfacePath: source,
    });
  });

  it("captures a source tab including its query before an async action finishes", () => {
    const host = document.createElement("div");
    host.dataset.onnoSurfacePath = "/reports?period=month";
    const child = host.appendChild(document.createElement("button"));
    const refresh = surfaceRefreshCallback(child);
    host.dataset.onnoSurfacePath = "/reports?period=year";
    const original = vi.fn();
    const changed = vi.fn();
    const unsubscribeOriginal = subscribeSurfaceRefresh("/reports?period=month", original);
    const unsubscribeChanged = subscribeSurfaceRefresh("/reports?period=year", changed);
    refresh();
    expect(original).toHaveBeenCalledOnce();
    expect(changed).not.toHaveBeenCalled();
    unsubscribeOriginal();
    unsubscribeChanged();
    refresh();
    expect(original).toHaveBeenCalledOnce();
  });

  it.each(["primary", "menu", "form"])("refreshes a %s record action without SSE", async (placement) => {
    const item: ActionItem = {
      label: "Advance", url: "onno://action/documents/orders/advance/o1",
      placement: placement === "menu" ? "menu" : "primary",
      ...(placement === "form" ? { form: [{ key: "note", label: "Note", type: "text" }] as never } : {}),
    };
    render(<MemoryRouter>{pane(source)}{pane(other)}
      <div data-onno-surface-path={source}><ActionsCluster items={[item]} /></div>
    </MemoryRouter>);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    if (placement === "menu") fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(await screen.findByRole("button", { name: "Advance" }));
    if (placement === "form") fireEvent.click(screen.getByRole("button", { name: "Submit action form" }));
    await waitFor(() => expect(refreshed).toHaveBeenCalledOnce());
    expect(applyDelta).not.toHaveBeenCalled();
    expect(runAction).toHaveBeenCalledWith("documents", "orders", "advance", "o1",
      placement === "form" ? { note: "Test" } : undefined);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("refreshes a page action's actual tab instead of a different tab of the same route", async () => {
    const path = "/reports?period=month";
    render(<MemoryRouter>{pane(path)}{pane("/reports?period=year")}
      <div data-onno-surface-path={path}>
        <PageActionsBar route="/reports" buttons={[{ key: "rebuild", label: "Rebuild", server: true }]} />
      </div>
    </MemoryRouter>);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole("button", { name: "Rebuild" }));
    await waitFor(() => expect(refreshed).toHaveBeenCalledOnce());
    expect(applyDelta).not.toHaveBeenCalled();
    expect(refreshed).toHaveBeenCalledWith(path);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("does not refresh a page action that returns only feedback", async () => {
    runPageAction.mockResolvedValue({ refresh: false });
    render(<MemoryRouter>{pane("/reports")}
      <PageActionsBar route="/reports" buttons={[{ key: "check", label: "Check", server: true }]} />
    </MemoryRouter>);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "Check" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Check" })).toBeEnabled());
    expect(runPageAction).toHaveBeenCalledOnce();
    expect(applyDelta).not.toHaveBeenCalled();
    expect(refreshed).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
