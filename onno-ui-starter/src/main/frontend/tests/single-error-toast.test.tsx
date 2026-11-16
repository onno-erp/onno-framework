import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ImgHTMLAttributes, ReactNode } from "react";
import type { AttributeMeta, DashboardWidgetMeta, ProcessWorkItem, RelatedListMeta } from "@/lib/types";

// Widget-level guard for #505: with the real api layer in place (only fetch is stubbed), one
// failed request shows one toast — the widget's own contextual one or the api layer's, not both.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

vi.mock("@/hooks/use-ui-events", () => ({ useUiEvents: () => undefined }));

vi.mock("@/components/ui/avatar", () => ({
  Avatar: ({ children }: { children: ReactNode }) => <span>{children}</span>,
  AvatarImage: (props: ImgHTMLAttributes<HTMLImageElement>) => <img {...props} />,
  AvatarFallback: ({ children }: { children: ReactNode }) => <span>{children}</span>,
}));

// The related-list "Add" picker: one button that picks a target, so the add path runs for real.
vi.mock("@/components/ref-select", () => ({
  RefSelect: ({ onChange }: { onChange: (id: string) => void }) => (
    <button type="button" onClick={() => onChange("tag-2")}>Pick tag</button>
  ),
}));

import { SettingWidget } from "@/components/setting-widget";
import { ProcessTasksWidget } from "@/components/process-tasks-widget";
import { RelatedListPanel } from "@/components/related-list-panel";

type Route = (url: string, method: string) => Response;

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

const networkDown = (): Response => {
  throw new TypeError("Failed to fetch");
};

/** Stub fetch with a router: return a response, or throw to simulate a network failure. */
function stubFetch(route: Route) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) =>
    route(url, (init?.method ?? "GET").toUpperCase())));
}

/** Let any late second toast land before asserting there is exactly one. */
async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

/** Wait for the first error toast, then make sure no second one follows. */
async function toastsAfterFailure() {
  await waitFor(() => expect(toast.error).toHaveBeenCalled());
  await settle();
  return toast.error.mock.calls;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("one failed request, one toast", () => {
  describe("SettingWidget save", () => {
    const widget = { title: "Company", extraConfig: { constant: "CompanyName" } } as unknown as DashboardWidgetMeta;

    const save = async (put: () => Response) => {
      stubFetch((url, method) => {
        if (url === "/api/settings" && method === "GET") {
          return response(200, [
            { name: "CompanyName", displayName: "Company name", type: "String", widget: "text", value: "Acme" },
          ]);
        }
        if (url === "/api/settings" && method === "PUT") return put();
        throw new Error(`unexpected ${method} ${url}`);
      });
      render(<SettingWidget widget={widget} />);
      fireEvent.change(await screen.findByDisplayValue("Acme"), { target: { value: "Acme Ltd" } });
      fireEvent.click(screen.getByRole("button", { name: "Save" }));
    };

    it("shows only its own 'Couldn't save' toast", async () => {
      await save(() => response(500, { message: "Database unavailable" }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't save: Database unavailable"]]);
    });

    it("reports typed feedback, which the api layer leaves to it, once with its context", async () => {
      await save(() => response(409, {
        severity: "error",
        presentation: "toast",
        title: "Setting locked",
        message: "Locked by an administrator",
      }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't save: Locked by an administrator"]]);
    });

    it("reports a lapsed session (401), which the api layer leaves to it, once with its context", async () => {
      await save(() => response(401, { error: "unauthenticated" }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't save: unauthenticated"]]);
    });
  });

  describe("ProcessTasksWidget", () => {
    const open = {
      id: "task-1",
      instanceId: "instance-1",
      definitionKey: "order-approval",
      definitionVersion: 1,
      stepKey: "review",
      title: "Review order O-42",
      status: "OPEN",
      assigneeId: null,
      assignee: null,
      outcomes: ["APPROVE", "REJECT"],
    } as ProcessWorkItem;
    const claimed = { ...open, status: "CLAIMED", assigneeId: "employee-1", assignee: "Mara" } as ProcessWorkItem;
    const widget = { title: "Process tasks" } as DashboardWidgetMeta;

    /** The inbox lists `task`; `route` answers the one action under test. */
    const inbox = (task: ProcessWorkItem, route: Route): Route => (url, method) => {
      if (url === "/api/tasks" && method === "GET") {
        return response(200, { rows: [task], nextCursor: null, hasMore: false });
      }
      if (url.startsWith("/api/task-assignees")) {
        return response(200, [
          { actorId: "employee-1", username: "mara@example.test", display: "Mara", avatarUrl: null },
          { actorId: "employee-2", username: "mina@example.test", display: "Mina Lee", avatarUrl: null },
        ]);
      }
      return route(url, method);
    };
    const only = (path: string, method: string, answer: () => Response): Route => (url, m) => {
      if (url === path && m === method) return answer();
      throw new Error(`unexpected ${m} ${url}`);
    };

    it("claim: shows the api layer's toast once when the server rejects it", async () => {
      stubFetch(inbox(open, only("/api/tasks/task-1/claim", "POST", () => response(409, { message: "Task is already claimed" }))));

      render(<ProcessTasksWidget widget={widget} />);
      fireEvent.click(await screen.findByRole("button", { name: "Claim" }));

      expect(await toastsAfterFailure()).toEqual([["Task is already claimed"]]);
    });

    it("claim: still reports a network failure, which the api layer does not toast", async () => {
      stubFetch(inbox(open, only("/api/tasks/task-1/claim", "POST", networkDown)));

      render(<ProcessTasksWidget widget={widget} />);
      fireEvent.click(await screen.findByRole("button", { name: "Claim" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });

    it("complete: shows the api layer's toast once", async () => {
      stubFetch(inbox(claimed, only("/api/tasks/task-1/complete", "POST", () => response(409, { message: "Task is already completed" }))));

      render(<ProcessTasksWidget widget={widget} />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "Approve" }));
      fireEvent.click(screen.getByRole("button", { name: "Confirm Approve" }));

      expect(await toastsAfterFailure()).toEqual([["Task is already completed"]]);
    });

    it("history: shows the api layer's toast once", async () => {
      stubFetch(inbox(claimed, only("/api/tasks/task-1/history", "GET", () => response(500, { message: "History is unavailable" }))));

      render(<ProcessTasksWidget widget={widget} />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "History" }));

      expect(await toastsAfterFailure()).toEqual([["History is unavailable"]]);
    });

    it("delegate: shows the api layer's toast once", async () => {
      stubFetch(inbox(claimed, only("/api/tasks/task-1/delegate", "POST", () => response(403, { message: "Only the assignee can delegate" }))));

      render(<ProcessTasksWidget widget={widget} />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "Delegate" }));
      fireEvent.click(await screen.findByRole("button", { name: /Mina Lee/i }));
      fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Covering annual leave" } });
      fireEvent.click(screen.getByRole("button", { name: "Delegate" }));

      expect(await toastsAfterFailure()).toEqual([["Only the assignee can delegate"]]);
    });
  });

  describe("RelatedListPanel", () => {
    const meta: RelatedListMeta = {
      name: "tags",
      label: "Tags",
      sourceName: "order_tags",
      viaField: "order",
      displayField: "tag",
      target: "Tags",
      targetKind: "catalog",
      columns: [{ fieldName: "tag", columnName: "tag", displayName: "Tag", javaType: "Ref" } as AttributeMeta],
    };
    const related = "/api/catalogs/orders/o-1/related/tags";
    const renderPanel = () =>
      render(<RelatedListPanel parentKind="catalogs" parentName="orders" parentId="o-1" meta={meta} />);

    it("load: shows only its own 'Couldn't load' toast", async () => {
      stubFetch((url) => {
        if (url === related) return response(500, { message: "boom" });
        throw new Error(`unexpected ${url}`);
      });

      renderPanel();

      expect(await toastsAfterFailure()).toEqual([["Couldn't load Tags: boom"]]);
    });

    describe("add", () => {
      const add = async (create: () => Response) => {
        stubFetch((url, method) => {
          if (url === related) return response(200, { rows: [], nextCursor: null, hasMore: false });
          if (url === "/api/catalogs/order_tags" && method === "POST") return create();
          throw new Error(`unexpected ${method} ${url}`);
        });
        renderPanel();
        fireEvent.click(await screen.findByRole("button", { name: /add/i }));
        fireEvent.click(await screen.findByRole("button", { name: "Pick tag" }));
      };

      it("shows only its own 'Couldn't add' toast", async () => {
        await add(() => response(409, { message: "Tag is already linked" }));

        expect(await toastsAfterFailure()).toEqual([["Couldn't add: Tag is already linked"]]);
      });

      it("keeps its context for a network failure", async () => {
        await add(networkDown);

        expect(await toastsAfterFailure()).toEqual([["Couldn't add: Failed to fetch"]]);
      });
    });

    it("remove: shows only its own 'Couldn't remove' toast", async () => {
      stubFetch((url, method) => {
        if (url === related) return response(200, {
          rows: [{ id: "row-1", tag: "t-1", tagDisplay: "Urgent" }], nextCursor: null, hasMore: false,
        });
        if (url === "/api/catalogs/order_tags/row-1" && method === "DELETE") {
          return response(409, { message: "Row is locked" });
        }
        throw new Error(`unexpected ${method} ${url}`);
      });

      renderPanel();
      fireEvent.click(await screen.findByRole("button", { name: "Remove row" }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't remove: Row is locked"]]);
    });
  });
});
