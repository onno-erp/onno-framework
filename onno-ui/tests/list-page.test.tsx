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
    };
  }) => (
    <p>
      {list.columns.map((c) => c.label).join(",")}|{list.sort.column}|
      {String(list.embedded)}
    </p>
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
        fields={{ description: { label: "Title" } }}
        sort="description"
      />,
    );
    expect(screen.getByText("Title|description|undefined")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(list.columns[0].label).toBe("Description");
  });
});
