import * as React from "react";
import { render, screen, fireEvent, cleanup, waitFor, act } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
const state = vi.hoisted(() => ({ list: vi.fn(), refresh: () => {} }));
vi.mock("@onno/widget-sdk", async () => ({ ...React, api: { listCatalog: state.list }, useUiEvents: (fn: () => void) => { state.refresh = fn; },
  ...await import("../src/components/ui/badge"), ...await import("../src/components/ui/select") }));
import { ContactStageLabel, ContactStagePicker, useContactStages } from "../../../../../onno-crm-starter/src/main/widgets/ContactStage";
beforeEach(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });
it("loads user-defined choices, uses catalog colors, and refreshes after catalog edits", async () => {
  state.list.mockResolvedValue([{id:"custom",description:"Awaiting contract",color:"#123456"}]);
  function Panel() {
    const { stages } = useContactStages();
    return <><ContactStageLabel stage={stages[0]} /><ContactStagePicker stages={stages} value="" label="Stage" onChange={() => {}} /></>;
  }
  render(<Panel />);
  const label = await screen.findByText("Awaiting contract");
  expect(label.parentElement?.style.backgroundColor).toBe("rgb(18, 52, 86)");
  fireEvent.click(screen.getByRole("combobox"));
  expect(await screen.findByRole("option",{name:"Awaiting contract"})).toBeTruthy();
  expect(screen.queryByRole("option",{name:"Hot lead"})).toBeNull();
  state.list.mockResolvedValue([{id:"custom",description:"Contract signed",color:"#00AA00"}]);
  act(() => state.refresh());
  await waitFor(() => expect(screen.queryByText("Awaiting contract")).toBeNull());
  expect(screen.getAllByText("Contract signed")[0].parentElement?.style.backgroundColor).toBe("rgb(0, 170, 0)");
});
