import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { type AttributeMeta, SECRET_SET } from "@/lib/types";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function toSnakeCase(str: string): string {
  return str
    .replace(/([a-z])([A-Z])/g, "$1_$2")
    .replace(/\s+/g, "_")
    .toLowerCase();
}

export function displayValue(attr: AttributeMeta, raw: unknown, row?: Record<string, unknown>): string {
  // Secrets are write-only: the server returns a "set" sentinel or null, never the value.
  if (attr.secret) {
    const stored = row ? row[attr.columnName] : raw;
    return stored === SECRET_SET || (stored != null && stored !== "") ? "•••• set" : "Not set";
  }
  // Prefer server-resolved display value
  if (row) {
    const ref = row[attr.columnName + "_ref"];
    if (ref && typeof ref === "object" && "display" in ref) {
      return String((ref as { display?: unknown }).display ?? "");
    }
    const display = row[attr.columnName + "_display"];
    if (display != null) return String(display);
  }
  if (raw == null) return "";
  if (attr.isEnum && attr.enumValues) {
    const found = attr.enumValues.find((v) => v.id === raw);
    return found ? found.name : String(raw);
  }
  return String(raw);
}
