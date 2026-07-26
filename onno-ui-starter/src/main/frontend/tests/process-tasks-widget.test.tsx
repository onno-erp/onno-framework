import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ImgHTMLAttributes, ReactNode } from "react";
import type { DashboardWidgetMeta, ProcessWorkItem, UiEvent } from "@/lib/types";

const {
  listProcessTasks,
  claimProcessTask,
  completeProcessTask,
  searchTaskAssignees,
  delegateProcessTask,
  getProcessTaskHistory,
  uiHandlers,
} = vi.hoisted(() => ({
  listProcessTasks: vi.fn(),
  claimProcessTask: vi.fn(),
  completeProcessTask: vi.fn(),
  searchTaskAssignees: vi.fn(),
  delegateProcessTask: vi.fn(),
  getProcessTaskHistory: vi.fn(),
  uiHandlers: [] as Array<(event: UiEvent) => void>,
}));

vi.mock("@/lib/api", () => ({
  api: {
    listProcessTasks,
    claimProcessTask,
    completeProcessTask,
    searchTaskAssignees,
    delegateProcessTask,
    getProcessTaskHistory,
  },
}));

vi.mock("@/hooks/use-ui-events", () => ({
  useUiEvents: (handler: (event: UiEvent) => void) => {
    uiHandlers[0] = handler;
  },
}));

vi.mock("@/components/ui/avatar", () => ({
  Avatar: ({ children }: { children: ReactNode }) => <span>{children}</span>,
  AvatarImage: (props: ImgHTMLAttributes<HTMLImageElement>) => <img {...props} />,
  AvatarFallback: ({ children }: { children: ReactNode }) => <span>{children}</span>,
}));

import { ProcessTasksWidget } from "@/components/process-tasks-widget";

const widget = {
  title: "Process tasks",
  hint: "Live inbox",
} as DashboardWidgetMeta;

const task = {
  id: "task-1",
  instanceId: "instance-1",
  definitionKey: "order-approval",
  stepKey: "review",
  title: "Review order O-42",
  status: "OPEN",
  assigneeId: null,
  assignee: null,
  outcomes: ["APPROVE", "REJECT"],
} as ProcessWorkItem;

beforeEach(() => {
  searchTaskAssignees.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  listProcessTasks.mockReset();
  claimProcessTask.mockReset();
  completeProcessTask.mockReset();
  searchTaskAssignees.mockReset();
  delegateProcessTask.mockReset();
  getProcessTaskHistory.mockReset();
  uiHandlers.length = 0;
});

describe("ProcessTasksWidget live inbox", () => {
  it("refetches on the shared tasks-changed SSE event without a refresh button", async () => {
    listProcessTasks.mockResolvedValueOnce([]).mockResolvedValueOnce([task]);
    render(<ProcessTasksWidget widget={widget} />);

    await screen.findByText("You have no open tasks.");
    expect(screen.queryByRole("button", { name: /refresh tasks/i })).not.toBeInTheDocument();

    await act(async () => {
      uiHandlers[0]({ type: "tasks-changed", entityType: "process-task", id: "instance-1" });
    });

    expect(await screen.findByText("Review order O-42")).toBeInTheDocument();
    expect(listProcessTasks).toHaveBeenCalledTimes(2);
  });

  it("shows Retry only when loading the inbox failed", async () => {
    listProcessTasks.mockRejectedValueOnce(new Error("Network unavailable")).mockResolvedValueOnce([]);
    render(<ProcessTasksWidget widget={widget} />);

    expect(await screen.findByText("Network unavailable")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(listProcessTasks).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("You have no open tasks.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  it("delegates a claimed task through the employee picker with a reason", async () => {
    const claimed = {
      ...task, status: "CLAIMED", assigneeId: "employee-1", assignee: "Mara",
      subject: {
        kind: "documents", entityName: "Orders", id: "order-42", label: "Order O-42",
      },
    } as ProcessWorkItem;
    listProcessTasks.mockResolvedValueOnce([claimed]).mockResolvedValueOnce([]);
    searchTaskAssignees.mockResolvedValue([
      {
        actorId: "employee-1",
        username: "mara@example.test",
        display: "Mara",
        avatarUrl: "https://images.example.test/mara.jpg",
      },
      {
        actorId: "employee-2",
        username: "mina@example.test",
        display: "Mina Lee",
        avatarUrl: "https://images.example.test/mina.jpg",
      },
    ]);
    delegateProcessTask.mockResolvedValue({
      ...claimed,
      assignee: "mina@example.test",
    });
    render(<ProcessTasksWidget widget={widget} />);

    await screen.findByText("Review order O-42");
    expect((await screen.findAllByRole("img", { name: "Mara" }))[0])
      .toHaveAttribute("src", "https://images.example.test/mara.jpg");
    const subjectLink = screen.getByRole("link", { name: "Open Order O-42" });
    expect(subjectLink)
      .toHaveAttribute("href", "/documents/orders/order-42");
    const action = vi.fn();
    window.addEventListener("onno:action", action);
    fireEvent.click(subjectLink);
    expect(action).toHaveBeenCalledOnce();
    expect((action.mock.calls[0][0] as CustomEvent).detail)
      .toBe("onno://documents/orders/order-42");
    window.removeEventListener("onno:action", action);
    fireEvent.click(screen.getByRole("button", { name: "Delegate" }));
    expect(await screen.findByRole("img", { name: "Mina Lee" }))
      .toHaveAttribute("src", "https://images.example.test/mina.jpg");
    fireEvent.click(await screen.findByRole("button", { name: /Mina Lee/i }));
    fireEvent.change(screen.getByLabelText("Reason"), {
      target: { value: "Covering annual leave" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Delegate" }));

    await waitFor(() => expect(delegateProcessTask).toHaveBeenCalledWith(
      "task-1", "employee-2", "Covering annual leave",
    ));
    expect(await screen.findByText("You have no open tasks.")).toBeInTheDocument();
  });

  it("separates mine from available work and searches the visible queue", async () => {
    const available = { ...task, id: "available", title: "Review order O-10" } as ProcessWorkItem;
    const mine = {
      ...task,
      id: "mine",
      title: "Review order O-20",
      status: "CLAIMED",
      assigneeId: "employee-1",
      assignee: "Mara",
    } as ProcessWorkItem;
    listProcessTasks.mockResolvedValue([available, mine]);
    render(<ProcessTasksWidget widget={widget} />);

    await screen.findByText("Review order O-10");
    fireEvent.click(screen.getByRole("button", { name: "Mine 1" }));
    expect(screen.queryByText("Review order O-10")).not.toBeInTheDocument();
    expect(screen.getByText("Review order O-20")).toBeInTheDocument();

    fireEvent.change(screen.getByRole("textbox", { name: "Search tasks" }), {
      target: { value: "does-not-exist" },
    });
    expect(screen.getByText("No tasks match this view.")).toBeInTheDocument();
  });

  it("confirms an outcome before completing the task", async () => {
    const claimed = {
      ...task,
      status: "CLAIMED",
      assigneeId: "employee-1",
      assignee: "Mara",
    } as ProcessWorkItem;
    listProcessTasks.mockResolvedValueOnce([claimed]).mockResolvedValueOnce([]);
    completeProcessTask.mockResolvedValue({});
    render(<ProcessTasksWidget widget={widget} />);

    await screen.findByText("Review order O-42");
    fireEvent.click(screen.getByRole("button", { name: "Approve", exact: true }));
    expect(completeProcessTask).not.toHaveBeenCalled();
    expect(screen.getByRole("alertdialog", { name: "Approve task?" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Confirm Approve" }));
    await waitFor(() => expect(completeProcessTask).toHaveBeenCalledWith("task-1", "APPROVE"));
    expect(await screen.findByText("You have no open tasks.")).toBeInTheDocument();
  });

  it("shows readable timestamped history without repeating the assignee", async () => {
    const claimed = {
      ...task,
      status: "CLAIMED",
      assigneeId: "employee-1",
      assignee: "Mara",
    } as ProcessWorkItem;
    listProcessTasks.mockResolvedValue([claimed]);
    searchTaskAssignees.mockResolvedValue([
      {
        actorId: "employee-1",
        username: "mara@example.test",
        display: "Mara",
        avatarUrl: "https://images.example.test/mara.jpg",
      },
    ]);
    getProcessTaskHistory.mockResolvedValue([
      {
        id: "event-1",
        workItemId: "task-1",
        instanceId: "instance-1",
        type: "CLAIMED",
        actorId: "employee-1",
        actor: "Mara",
        toAssigneeId: "employee-1",
        toAssignee: "Mara",
        occurredAt: new Date().toISOString(),
        sequence: 2,
      },
    ]);
    render(<ProcessTasksWidget widget={widget} />);

    await screen.findByText("Review order O-42");
    fireEvent.click(screen.getByRole("button", { name: "History" }));
    expect(await screen.findByText("Mara claimed the task")).toBeInTheDocument();
    expect((await screen.findAllByRole("img", { name: "Mara" }))[0])
      .toHaveAttribute("src", "https://images.example.test/mara.jpg");
    expect(screen.queryByText(/Mara → Mara/)).not.toBeInTheDocument();
  });
});
