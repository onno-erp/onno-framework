import {cleanup, fireEvent, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {WorkspaceToolbar} from "@/components/ui/workspace-toolbar";
afterEach(cleanup);
it("orders authorized controls, hides omitted items, and applies compact overrides", () => {
  const items = ["title","save","delete","search"].map(id => ({id,content:<button>{id}</button>}));
  const layout = {start:["title"],end:["save"],secondary:["search"]};
  const view = render(<WorkspaceToolbar items={items} layout={layout} />);
  expect(screen.queryByText("delete")).toBeNull();
  expect(screen.getAllByRole("button").map(el=>el.textContent)).toEqual(["title","save","search"]);
  view.rerender(<WorkspaceToolbar items={items} layout={layout} compact compactLayout={{start:[],end:["save","title"],hidden:["search"]}} />);
  expect(screen.getAllByRole("button").map(el=>el.textContent)).toEqual(["save","title"]);
});
it("renders duplicates once and preserves handlers and disabled state in overflow", () => {
  const action = vi.fn();
  render(<WorkspaceToolbar items={[{id:"save",content:<button onClick={action}>Save</button>},{id:"delete",content:<button disabled>Delete</button>}]} layout={{start:["save"],end:["save"],overflow:["delete","unknown"]}} />);
  expect(screen.getAllByText("Save")).toHaveLength(1);
  fireEvent.click(screen.getByText("Save"));
  expect(action).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button",{name:"More"}));
  expect(screen.getByText("Delete")).toBeDisabled();
});
