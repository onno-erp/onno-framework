import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { DashboardWidgetMeta, ProcessWorkItem, UiEvent } from "@/lib/types";

const { listProcessTasks, uiHandlers } = vi.hoisted(() => ({
  listProcessTasks: vi.fn(),
  uiHandlers: [] as Array<(event: UiEvent) => void>,
}));

vi.mock("@/lib/api", () => ({
  api: {
    listProcessTasks,
    claimProcessTask: vi.fn(),
    completeProcessTask: vi.fn(),
  },
}));

vi.mock("@/hooks/use-ui-events", () => ({
  useUiEvents: (handler: (event: UiEvent) => void) => {
    uiHandlers[0] = handler;
  },
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
  candidateUsers: [],
  candidateRoles: ["MANAGER"],
  assignee: null,
  outcomes: ["APPROVE", "REJECT"],
} as ProcessWorkItem;

afterEach(() => {
  cleanup();
  listProcessTasks.mockReset();
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
});
