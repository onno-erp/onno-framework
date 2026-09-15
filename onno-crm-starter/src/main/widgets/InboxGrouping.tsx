import { Button, registerExtension, useTranslate, type ExtensionProps } from "@onno/widget-sdk";

/**
 * Folders or everything, as a contribution to the inbox toolbar rather than a control the header
 * hardcodes.
 *
 * <p>Folding is a reading preference, not a filter: "Everything" drops the grouping without dropping
 * a single chat. It ships with the CRM because most inboxes want it, but it is registered the same
 * way an application's own button would be — so a deployment can hide it, reorder it, or replace it
 * by registering its own contribution under this id.
 */
function InboxGrouping({ context }: ExtensionProps) {
  const t = useTranslate();
  const flat = context.view?.get("grouping") === "flat";
  const choose = (value: string) => context.view?.set("grouping", value);
  return (
    <div className="flex flex-wrap items-center gap-1" aria-label={t("crm.inbox.grouping")}>
      <Button size="toolbar" variant={!flat ? "secondary" : "ghost"} aria-pressed={!flat}
        onClick={() => choose("folders")}>{t("crm.inbox.folders")}</Button>
      <Button size="toolbar" variant={flat ? "secondary" : "ghost"} aria-pressed={flat}
        onClick={() => choose("flat")}>{t("crm.inbox.everything")}</Button>
    </div>
  );
}

registerExtension({
  id: "onno.crm.inbox.grouping",
  slot: "crm.inbox.toolbar",
  order: 10,
  // Only worth offering where there is folding to drop, and only where the host has not said that
  // its folders carry the workspace's meaning rather than a preference the reader may turn off.
  visible: context => Number(context.record?.folders ?? 0) > 0 && context.record?.grouping !== "false",
  component: InboxGrouping,
});
