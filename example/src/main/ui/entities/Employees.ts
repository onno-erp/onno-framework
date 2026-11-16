import { User } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const employees = {
  icon: User,
  list: {
    columns: ["avatarUrl", "description", "email", "position"],
    // The photo column needs no header.
    labels: { avatarUrl: "" },
    sort: "description",
    filters: [{ field: "position", label: "Role" }],
  },
  fields: {
    description: { label: "Name" },
    avatarUrl: { label: "Photo", input: "avatar", hint: "Link to a staff photo." },
  },
} satisfies EntityCustomization<"Employees">;
