import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ImgHTMLAttributes, ReactNode } from "react";
import type { ProcessWorkItem } from "@/lib/types";

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

import { Setting } from "@/components/widgets/setting";
import { TaskInbox } from "@/components/widgets/task-inbox";

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
  describe("Setting save", () => {

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
      render(<Setting title="Company" constant="CompanyName" />);
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

  describe("TaskInbox", () => {
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

      render(<TaskInbox title="Process tasks" />);
      fireEvent.click(await screen.findByRole("button", { name: "Claim" }));

      expect(await toastsAfterFailure()).toEqual([["Task is already claimed"]]);
    });

    it("claim: still reports a network failure, which the api layer does not toast", async () => {
      stubFetch(inbox(open, only("/api/tasks/task-1/claim", "POST", networkDown)));

      render(<TaskInbox title="Process tasks" />);
      fireEvent.click(await screen.findByRole("button", { name: "Claim" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });

    it("complete: shows the api layer's toast once", async () => {
      stubFetch(inbox(claimed, only("/api/tasks/task-1/complete", "POST", () => response(409, { message: "Task is already completed" }))));

      render(<TaskInbox title="Process tasks" />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "Approve" }));
      fireEvent.click(screen.getByRole("button", { name: "Confirm Approve" }));

      expect(await toastsAfterFailure()).toEqual([["Task is already completed"]]);
    });

    it("history: shows the api layer's toast once", async () => {
      stubFetch(inbox(claimed, only("/api/tasks/task-1/history", "GET", () => response(500, { message: "History is unavailable" }))));

      render(<TaskInbox title="Process tasks" />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "History" }));

      expect(await toastsAfterFailure()).toEqual([["History is unavailable"]]);
    });

    it("delegate: shows the api layer's toast once", async () => {
      stubFetch(inbox(claimed, only("/api/tasks/task-1/delegate", "POST", () => response(403, { message: "Only the assignee can delegate" }))));

      render(<TaskInbox title="Process tasks" />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "Delegate" }));
      fireEvent.click(await screen.findByRole("button", { name: /Mina Lee/i }));
      fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Covering annual leave" } });
      fireEvent.click(screen.getByRole("button", { name: "Delegate" }));

      expect(await toastsAfterFailure()).toEqual([["Only the assignee can delegate"]]);
    });
  });
});
