import { Button, formatMoney, formatRecordNumber, openRecord, openRoute, useRecords, type RouteProps, type UiExtension } from "@onno/ui";
import { ContactRound, ShoppingBag } from "@onno/ui/icons";
import {
  CrmConnections, CrmContactPage, CrmConversationPage, CrmInbox,
  type CrmInboxProps, type CrmTimelineSource,
} from "@onno/crm";
import { channelSetups } from "@onno/crm-channels";

/**
 * The customer's orders, as cards in the chat's timeline at the date they were placed: the
 * conversation and the business it is about, read in one place. A timeline source is a hook, so it
 * uses the app's own data hooks; the array is module-level so the hook order never changes.
 */
const orderCards: CrmTimelineSource[] = [{
  id: "orders",
  useEntries: ({ customerId }) => {
    const { data: orders = [] } = useRecords("Orders", { where: { customer: customerId }, sort: "-date", limit: 5 });
    return orders.filter(order => !!customerId && !!order.date).map(order => ({
      id: String(order.id),
      at: String(order.date),
      type: "custom" as const,
      render: () => (
        <div className="mx-auto flex max-w-md items-center gap-3 rounded-panel border border-border bg-muted/40 px-3 py-2 text-sm">
          <ShoppingBag className="size-4 shrink-0 text-primary" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate">Order {formatRecordNumber(order.number)} · {formatMoney(Number(order.total ?? 0), "USD")}</span>
          <Button size="sm" variant="ghost" onClick={() => openRecord("Orders", String(order.id))}>Open</Button>
        </div>
      ),
    }));
  },
}];

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
  timelineSources: orderCards,
};

/** Chat outlets: quote a message into the reply, and open the contact's full page. */
export const supportExtensions: UiExtension[] = [
  {
    id: "books.crm.quote",
    slot: "crm.chat.message.actions",
    component: ({ context }) => (
      <Button size="sm" variant="ghost" aria-label="Quote in reply"
        onClick={() => context.insertDraft?.(String(context.record?.body ?? "").split("\n").map(line => `> ${line}`).join("\n"))}>
        Quote
      </Button>
    ),
  },
  {
    id: "books.crm.contact-page",
    slot: "crm.chat.header",
    // Not on the contact page itself.
    visible: () => !window.location.pathname.includes("/support/contacts/"),
    component: ({ context }) => context.record?.customer ? (
      <Button size="toolbar" variant="ghost" onClick={() => openRoute(`/support/contacts/${String(context.record?.customer)}`)}>
        <ContactRound className="size-4" aria-hidden="true" />Contact page
      </Button>
    ) : null,
  },
];

/** The CRM inbox of the "support" workspace. */
export function SupportPage() {
  return <CrmInbox workspace="support" {...inbox} />;
}

/** A conversation link (`onno://catalogs/crm_conversations/<id>`) opens the chat in the inbox. */
export function ConversationPage({ params }: RouteProps) {
  return <CrmConversationPage params={params} {...inbox} />;
}

/** A customer's page: profile, conversations and the chat with their orders in it. */
export function ContactPage({ params }: RouteProps) {
  return <CrmContactPage contact={String(params.id ?? "")} workspace="support" timelineSources={orderCards}
    contactFields={inbox.contactFields} />;
}

/** The channel accounts the inbox sends and receives through, with the bundled channels' setup forms. */
export function ConnectionsPage() {
  return <CrmConnections setups={[channelSetups]} />;
}
