import {
  EntityList,
  RecordPage,
  RecordFields,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";

export function EmployeesPage({ list }: ListPageProps<"Employees">) {
  return (
    <EntityList
      list={list}
      columns={["avatarUrl", "description", "email", "position"]}
      labels={{ description: "Name", avatarUrl: "" }}
      sort={"description"}
      filters={[{ field: "position", label: "Role" }]}
      fields={{
        description: { label: "Name" },
        email: {},
        position: {},
        avatarUrl: {
          label: "Photo",
          input: "avatar",
          hint: "Link to a staff photo.",
        },
      }}
    />
  );
}

export function EmployeePage({ form }: RecordPageProps<"Employees">) {
  return (
    <RecordPage form={form}>
      <RecordFields
        form={form}
        fields={{
          description: { label: "Name" },
          email: {},
          position: {},
          avatarUrl: {
            label: "Photo",
            input: "avatar",
            hint: "Link to a staff photo.",
          },
        }}
      />
    </RecordPage>
  );
}
