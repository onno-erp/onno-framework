import { Truck } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const suppliers = {
  icon: Truck,
  list: { columns: ["description", "email", "phone"], sort: "description" },
  fields: {
    description: { label: "Name" },
    // Contact details get their own card, side by side on wide screens.
    email: { group: "Contact", width: "half" },
    phone: { group: "Contact", width: "half" },
    note: { input: "textarea" },
    contacts: { label: "Contact people" },
    "contacts.email": { placeholder: "name@example.com" },
  },
} satisfies EntityCustomization<"Suppliers">;
