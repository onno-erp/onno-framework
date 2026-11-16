import type { RouteProps } from "@onno/ui";
import { CrmInbox, type CrmInboxProps } from "./CrmInbox";

/**
 * A conversation's record route, opened in the inbox workspace that holds it. Add it to the app's
 * pages so conversation links (notifications, references, `onno://catalogs/crm_conversations/<id>`)
 * open the chat rather than a bare catalog form:
 *
 *   { path: "/catalogs/crm_conversations/:id", component: CrmConversationPage, view: false }
 *
 * To present it like the app's inbox page, pass the same props from a wrapper:
 * `(route) => <CrmConversationPage {...route} list={…} />`.
 */
export function CrmConversationPage(props: Pick<RouteProps, "params"> & Partial<Omit<RouteProps, "params">> & Omit<CrmInboxProps, "conversation" | "workspace">) {
  const { params, path, query, view, renderDefault, ...inbox } = props;
  return <CrmInbox {...inbox} conversation={params.id} />;
}
