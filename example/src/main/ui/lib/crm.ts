import { api } from "@onno/ui";

/**
 * Whether the CRM inbox exists: it is only wired under the `crm` Spring profile (see
 * InboxConfiguration), so the app asks its API once and remembers the answer.
 */
let available: Promise<boolean> | null = null;

export function crmInboxAvailable(): Promise<boolean> {
  available ??= api.getJson<unknown>("/api/crm/inbox-workspaces").then(() => true, () => false);
  return available;
}
