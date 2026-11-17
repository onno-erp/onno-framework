import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("@/components/lists/entity-list", () => ({
  EntityListView: ({
    list,
  }: {
    list: {
      columns: { label: string }[];
      sort: { column: string | null };
      embedded?: boolean;
      calendar?: { startField: string };
      timeline?: { laneField?: string };
    };
  }) => (
    <>
      <p>
        {list.columns.map((c) => c.label).join(",")}|{list.sort.column}|
        {String(list.embedded)}
      </p>
      <span data-testid="views">
        calendar:{list.calendar?.startField ?? "-"} timeline:{list.timeline?.laneField ?? "-"}
      </span>
    </>
  ),
}));
import { EntityList } from "@/components/pages/page-blocks";
import type { ListDescriptor } from "@/components/lists/entity-list";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("injected list pages", () => {
  it("applies page props to the injected descriptor without fetching another view", () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const list: ListDescriptor = {
      kind: "catalogs",
      name: "books",
      title: "Books",
      pageSize: 20,
      searchable: false,
      newUrl: null,
      sort: { column: null, descending: false },
      columns: [
        {
          columnName: "description",
          fieldName: "description",
          label: "Description",
          width: "",
        },
        {
          columnName: "author",
          fieldName: "author",
          label: "Author",
          width: "",
        },
      ],
    };
    render(
      <EntityList
        list={list}
        columns={["description"]}
        overrides={{ description: { label: "Title" } }}
        sort="description"
      />,
    );
    expect(screen.getByText("Title|description|undefined")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(list.columns[0].label).toBe("Description");
  });

  it("forwards calendar and timeline props to the list", () => {
    vi.stubGlobal("fetch", vi.fn());
    const list = {
      kind: "documents", name: "events", title: "Events", pageSize: 20, searchable: false, newUrl: null,
      sort: { column: null, descending: false },
      columns: [
        { columnName: "starts_at", fieldName: "startsAt", label: "Starts", width: "" },
        { columnName: "ends_at", fieldName: "endsAt", label: "Ends", width: "" },
        { columnName: "organizer", fieldName: "organizer", label: "Organizer", width: "" },
      ],
    } as unknown as ListDescriptor;
    render(
      <EntityList
        list={list}
        calendar={{ start: "startsAt", end: "endsAt" }}
        timeline={{ start: "startsAt", end: "endsAt", lane: "organizer" }}
      />,
    );
    expect(screen.getByTestId("views").textContent).toBe("calendar:startsAt timeline:organizer");
  });
});
