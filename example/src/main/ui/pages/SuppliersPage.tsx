import {
  EntityList,
  Page,
  Row,
  Stat,
  count,
  RecordPage,
  RecordFields,
  RecordLines,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";

export function SuppliersPage({ list }: ListPageProps<"Suppliers">) {
  return (
    <Page title="Suppliers" subtitle="Who we buy stock from">
      <Row widths={[1, 2]}>
        <Stat
          title="Total suppliers"
          measure={count("Suppliers")}
          trend={false}
        />
        <div />
      </Row>
      <EntityList
        list={list}
        columns={["description", "email", "phone"]}
        labels={{ description: "Name" }}
        sort={"description"}
        fields={{
          description: { label: "Name" },
          // Contact details get their own card, side by side on wide screens.
          email: { group: "Contact", width: "half" },
          phone: { group: "Contact", width: "half" },
          note: { input: "textarea" },
          contacts: { label: "Contact people" },
          "contacts.name": {},
          "contacts.email": { placeholder: "name@example.com" },
          "contacts.phone": {},
        }}
      />
    </Page>
  );
}

export function SupplierPage({ form }: RecordPageProps<"Suppliers">) {
  return (
    <RecordPage form={form}>
      <RecordFields
        form={form}
        fields={{
          description: { label: "Name" },
          email: { group: "Contact", width: "half" },
          phone: { group: "Contact", width: "half" },
          note: { input: "textarea" },
          contacts: { label: "Contact people" },
        }}
      />
      <RecordLines
        form={form}
        section="contacts"
        fields={{
          name: {},
          email: { placeholder: "name@example.com" },
          phone: {},
        }}
      />
    </RecordPage>
  );
}
