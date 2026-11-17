import { Tag } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const categories = {
  icon: Tag,
  list: { columns: ["code", "description", "color"], sort: "description" },
  fields: {
    description: { label: "Name" },
    color: { input: "color", placeholder: "#RRGGBB" },
  },
} satisfies EntityCustomization<"Categories">;
