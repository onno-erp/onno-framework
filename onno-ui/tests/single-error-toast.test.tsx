import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ImgHTMLAttributes, ReactNode } from "react";
import type { ProcessWorkItem } from "@/lib/types";
import type { CommentView } from "@/lib/api";

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
import { CommentThread } from "@/components/collaboration/comments";

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

/** Answer only `method path`; any other request is a bug in the test. */
const only = (path: string, method: string, answer: () => Response): Route => (url, m) => {
  if (url === path && m === method) return answer();
  throw new Error(`unexpected ${m} ${url}`);
};

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

    /** The inbox's first page offers a second one; `next` answers the request for it. */
    const paged = (next: () => Response): Route => (url, method) => {
      if (url === "/api/tasks" && method === "GET") {
        return response(200, { rows: [open], nextCursor: "page-2", hasMore: true });
      }
      if (url === "/api/tasks?cursor=page-2" && method === "GET") return next();
      if (url.startsWith("/api/task-assignees")) return response(200, []);
      throw new Error(`unexpected ${method} ${url}`);
    };

    it("load more: shows the api layer's toast once", async () => {
      stubFetch(paged(() => response(500, { message: "Tasks are unavailable" })));

      render(<TaskInbox title="Process tasks" />);
      fireEvent.click(await screen.findByRole("button", { name: "Load more tasks" }));

      expect(await toastsAfterFailure()).toEqual([["Tasks are unavailable"]]);
    });

    it("load more: reports a network failure once", async () => {
      stubFetch(paged(networkDown));

      render(<TaskInbox title="Process tasks" />);
      fireEvent.click(await screen.findByRole("button", { name: "Load more tasks" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });

    /** The claimed task's history offers a second page; `next` answers the request for it. */
    const pagedHistory = (next: () => Response): Route => inbox(claimed, (url, method) => {
      if (url === "/api/tasks/task-1/history" && method === "GET") {
        return response(200, { rows: [], nextCursor: "page-2", hasMore: true });
      }
      if (url === "/api/tasks/task-1/history?cursor=page-2" && method === "GET") return next();
      throw new Error(`unexpected ${method} ${url}`);
    });

    const loadMoreHistory = async () => {
      render(<TaskInbox title="Process tasks" />);
      await screen.findByText("Review order O-42");
      fireEvent.click(screen.getByRole("button", { name: "History" }));
      fireEvent.click(await screen.findByRole("button", { name: "Load more history" }));
    };

    it("load more history: shows the api layer's toast once", async () => {
      stubFetch(pagedHistory(() => response(500, { message: "History is unavailable" })));
      await loadMoreHistory();

      expect(await toastsAfterFailure()).toEqual([["History is unavailable"]]);
    });

    it("load more history: reports a network failure once", async () => {
      stubFetch(pagedHistory(networkDown));
      await loadMoreHistory();

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });
  });

  describe("CommentThread", () => {
    const thread = "/api/comments/documents/orders/order-1";
    const comment: CommentView = {
      id: "comment-1",
      authorName: "Mara",
      authorAvatarUrl: null,
      body: "Ship it on Monday",
      parentId: null,
      mentions: [],
      reactions: [],
      createdAt: "2026-11-17T09:00:00Z",
      editedAt: null,
      mine: true,
      canDelete: true,
    };

    /** The thread lists `comment` with a second page behind it; `route` answers the action under test. */
    const discussion = (route: Route): Route => (url, method) => {
      if (url === thread && method === "GET") {
        return response(200, { rows: [comment], nextCursor: "page-2", hasMore: true });
      }
      return route(url, method);
    };

    const renderThread = async (route: Route) => {
      stubFetch(discussion(route));
      render(<CommentThread target={{ kind: "documents", name: "orders", id: "order-1" }} />);
      await screen.findByText("Ship it on Monday");
    };

    const send = async (answer: () => Response) => {
      await renderThread(only(thread, "POST", answer));
      fireEvent.change(screen.getByRole("textbox", { name: "Write a comment" }), { target: { value: "Agreed" } });
      fireEvent.click(screen.getByRole("button", { name: "Send comment" }));
    };

    it("load more: shows the api layer's toast once", async () => {
      await renderThread(only(`${thread}?cursor=page-2`, "GET", () => response(500, { message: "Comments are unavailable" })));
      fireEvent.click(screen.getByRole("button", { name: "Load more" }));

      expect(await toastsAfterFailure()).toEqual([["Comments are unavailable"]]);
    });

    it("load more: reports a network failure once", async () => {
      await renderThread(only(`${thread}?cursor=page-2`, "GET", networkDown));
      fireEvent.click(screen.getByRole("button", { name: "Load more" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });

    it("send: shows the api layer's toast once when the server rejects it", async () => {
      await send(() => response(422, { message: "Comment is too long" }));

      expect(await toastsAfterFailure()).toEqual([["Comment is too long"]]);
    });

    it("send: reports a lapsed session (401), which the api layer leaves to it, once with its context", async () => {
      await send(() => response(401, { error: "unauthenticated" }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't post comment: unauthenticated"]]);
    });

    it("send: reports field errors, which the api layer leaves to it, once with its context", async () => {
      await send(() => response(422, { message: "Body is required", fieldErrors: { body: ["Body is required"] } }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't post comment: Body is required"]]);
    });

    it("send: reports typed feedback, which the api layer leaves to it, once with its context", async () => {
      await send(() => response(422, { severity: "error", title: "Comments are closed", message: "The thread is locked" }));

      expect(await toastsAfterFailure()).toEqual([["Couldn't post comment: The thread is locked"]]);
    });

    it("send: reports a network failure once with its context", async () => {
      await send(networkDown);

      expect(await toastsAfterFailure()).toEqual([["Couldn't post comment: Failed to fetch"]]);
    });

    it("delete: reports a network failure once and puts the comment back", async () => {
      await renderThread(only("/api/comments/comment-1", "DELETE", networkDown));
      fireEvent.click(screen.getByRole("button", { name: "Delete comment" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
      expect(screen.getByText("Ship it on Monday")).toBeInTheDocument();
    });

    it("delete: shows the api layer's toast once when the server refuses", async () => {
      await renderThread(only("/api/comments/comment-1", "DELETE", () => response(403, { message: "Only the author can delete" })));
      fireEvent.click(screen.getByRole("button", { name: "Delete comment" }));

      expect(await toastsAfterFailure()).toEqual([["Only the author can delete"]]);
    });

    it("reaction: reports a network failure once", async () => {
      await renderThread(only("/api/comments/comment-1/reactions", "POST", networkDown));
      fireEvent.click(screen.getByRole("button", { name: "Choose reaction" }));
      fireEvent.click(screen.getByRole("button", { name: "React with 👍" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });
  });
});
