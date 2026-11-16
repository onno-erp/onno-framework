import { Users } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";
import { CustomerConversations } from "../components/CustomerConversations";

const CITIES = [
  "New York", "Los Angeles", "Chicago", "Toronto", "London", "Paris", "Berlin", "Madrid", "Rome",
  "Amsterdam", "Tokyo", "Singapore", "Dubai", "Mumbai", "Sydney", "São Paulo", "Mexico City",
  "Cape Town",
];

/** Customers: grouped by city with a map view; the form carries comments and a conversations panel. */
export const customers = {
  icon: Users,
  list: {
    columns: ["description", "status", "city", "email", "phone"],
    sort: "description",
    groupable: ["city"],
    groupBy: "city",
    filters: [
      { field: "city", label: "City", options: CITIES },
      { field: "email", label: "Email" },
    ],
    map: { lat: "latitude", lng: "longitude" },
  },
  fields: {
    description: { label: "Name" },
  },
  comments: true,
  recordPanels: [
    {
      component: CustomerConversations,
      title: "Client conversations",
      hint: "Recent client touchpoints and a mock AI relationship summary.",
    },
  ],
} satisfies EntityCustomization<"Customers">;
