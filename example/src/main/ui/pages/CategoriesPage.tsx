import {
  EntityList,
  RecordPage,
  RecordFields,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";

export function CategoriesPage({ list }: ListPageProps<"Categories">) {
  return (
    <EntityList
      list={list}
      columns={["code", "description", "color"]}
      labels={{ description: "Name" }}
      sort={"description"}
      fields={{
        description: { label: "Name" },
        color: { label: "Color", input: "color", placeholder: "#RRGGBB" },
      }}
    />
  );
}

export function CategoryPage({ form }: RecordPageProps<"Categories">) {
  return (
    <RecordPage form={form}>
      <RecordFields
        form={form}
        fields={{
          description: { label: "Name" },
          color: { label: "Color", input: "color", placeholder: "#RRGGBB" },
        }}
      />
    </RecordPage>
  );
}
