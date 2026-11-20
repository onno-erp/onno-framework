import { useMessages } from "../../providers/messages-provider";

/** A bounded widget must not imply that its loaded rows are the complete result. */
export function RecordsTruncated({ count, truncated }: { count: number; truncated: boolean }) {
  const t = useMessages();
  return truncated ? <p role="status" className="mb-2 text-xs text-muted-foreground">
    {t("widget.records.truncated", { count })}
  </p> : null;
}
