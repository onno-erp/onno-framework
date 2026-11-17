import type { RouteProps } from "@onno/ui";
import { CrmConnections, CrmConversationPage, CrmInbox, type CrmInboxProps } from "@onno/crm";
import { channelSetups } from "@onno/crm-channels";

/**
 * How the support inbox presents. Which conversations it holds and who may use it is the
 * "support" workspace bean (InboxConfiguration on the server).
 */
const inbox: Omit<CrmInboxProps, "workspace" | "conversation"> = {
  list: {
    columns: ["customer", "subject", "lastMessageAt", "unreadCount"],
    sort: "-lastMessageAt",
    filters: [
      { field: "subject", label: "Subject", type: "contains" },
      { field: "lastMessageAt", label: "Last message", type: "dateRange" },
    ],
  },
  contactFields: ["customer.description", "customer.email", "customer.phone", { key: "customer.city", label: "City" }],
  connectionsRoute: "/support/connections",
};

/** The CRM inbox of the "support" workspace. */
export function SupportPage() {
  return <CrmInbox workspace="support" {...inbox} />;
}

/** A conversation link (`onno://catalogs/crm_conversations/<id>`) opens the chat in the inbox. */
export function ConversationPage({ params }: RouteProps) {
  return <CrmConversationPage params={params} {...inbox} />;
}

/** The channel accounts the inbox sends and receives through, with the bundled channels' setup forms. */
export function ConnectionsPage() {
  return <CrmConnections setups={[channelSetups]} />;
}
