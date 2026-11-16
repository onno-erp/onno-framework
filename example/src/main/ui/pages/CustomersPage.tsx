import {
  EntityList,
  RecordPage,
  RecordFields,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";
import { CustomerConversations } from "../components/CustomerConversations";
const CITIES = [
  "New York",
  "Los Angeles",
  "Chicago",
  "Toronto",
  "London",
  "Paris",
  "Berlin",
  "Madrid",
  "Rome",
  "Amsterdam",
  "Tokyo",
  "Singapore",
  "Dubai",
  "Mumbai",
  "Sydney",
  "São Paulo",
  "Mexico City",
  "Cape Town",
];

export function CustomersPage({ list }: ListPageProps<"Customers">) {
  return (
    <EntityList
      list={list}
      columns={["description", "status", "city", "email", "phone"]}
      labels={{ description: "Name", status: "Status" }}
      sort={"description"}
      groupable={["city"]}
      groupBy={"city"}
      filters={[
        { field: "city", label: "City", options: CITIES },
        { field: "email", label: "Email" },
      ]}
      map={{ lat: "latitude", lng: "longitude" }}
      fields={{
        description: { label: "Name" },
        status: {},
        email: {},
        phone: {},
        city: {},
        latitude: {},
        longitude: {},
      }}
    />
  );
}

export function CustomerPage({ form }: RecordPageProps<"Customers">) {
  return (
    <RecordPage
      form={form}
      comments
      panels={[
        {
          component: CustomerConversations,
          title: "Client conversations",
          hint: "Recent client touchpoints and a mock AI relationship summary.",
        },
      ]}
    >
      <RecordFields
        form={form}
        fields={{
          description: { label: "Name" },
          status: {},
          email: {},
          phone: {},
          city: {},
          latitude: {},
          longitude: {},
        }}
      />
    </RecordPage>
  );
}
