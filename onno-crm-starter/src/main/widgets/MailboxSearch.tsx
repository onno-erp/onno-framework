import { Badge, Button, DialogShell, Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, toast, locale, text as t, useEffect, useLocale, useRef, useState, useTranslate } from "@onno/widget-sdk";
import { useId } from "react";
import { Loader2, MailSearch, Search } from "lucide-react";
import { restoreMailboxMessage, searchMailbox, type MailboxAccount, type MailboxMessage } from "./mailboxApi";

function messageDate(value: MailboxMessage["date"]): string {
  if (value == null || value === "") return "";
  const numeric = typeof value === "number" ? value : /^\d+$/.test(value) ? Number(value) : NaN;
  const date = new Date(Number.isFinite(numeric) ? numeric : String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const sameYear = date.getFullYear() === new Date().getFullYear();
  return new Intl.DateTimeFormat(locale(), sameYear ? { month: "short", day: "numeric" } : { year: "numeric", month: "short", day: "numeric" }).format(date);
}

const failure = (error: unknown, fallback: string) => error instanceof Error && error.message ? error.message : fallback;

/**
 * Finds a message in a connected mailbox, archived mail included, and brings it back: "Open" when the
 * CRM already holds its conversation, otherwise "Restore to inbox", which imports it and (where the
 * mailbox may be modified) moves it back to the Gmail inbox before opening the conversation.
 */
export function MailboxSearch({ open, onOpenChange, accounts, onOpenConversation }: {
  open: boolean; onOpenChange: (open: boolean) => void; accounts: MailboxAccount[];
  onOpenConversation: (conversationId: string) => void;
}) {
  useTranslate();
  useLocale();
  const accountId = useId(), queryId = useId(), hintId = useId();
  const [account, setAccount] = useState(accounts[0]?.address ?? "");
  const [query, setQuery] = useState("");
  const [messages, setMessages] = useState<MailboxMessage[]>([]);
  const [nextPage, setNextPage] = useState<string | null>(null);
  const [searched, setSearched] = useState<{ account: string; query: string } | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [moreError, setMoreError] = useState("");
  const [restoring, setRestoring] = useState<string | null>(null);
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});
  const sequence = useRef(0);
  const chosen = accounts.find(item => item.address === account) ?? accounts[0];
  useEffect(() => {
    if (!accounts.some(item => item.address === account)) setAccount(accounts[0]?.address ?? "");
  }, [accounts, account]);

  const run = async (target: { account: string; query: string }, pageToken?: string | null) => {
    if (!target.account || !target.query.trim()) return;
    const current = ++sequence.current;
    if (pageToken) setLoadingMore(true);
    else { setLoading(true); setMessages([]); setNextPage(null); setRowErrors({}); }
    setError(""); setMoreError("");
    setSearched(target);
    try {
      const page = await searchMailbox(target.account, target.query.trim(), pageToken);
      if (current !== sequence.current) return;
      setMessages(previous => {
        const merged = pageToken ? [...previous] : [];
        for (const message of page.messages) if (!merged.some(item => item.id === message.id)) merged.push(message);
        return merged;
      });
      setNextPage(page.nextPageToken);
    } catch (searchError) {
      if (current !== sequence.current) return;
      // A failed next page keeps the results already shown; only a failed search replaces them.
      if (pageToken) setMoreError(failure(searchError, t("crm.mailbox.error")));
      else setError(failure(searchError, t("crm.mailbox.error")));
    } finally {
      if (current === sequence.current) { setLoading(false); setLoadingMore(false); }
    }
  };

  const restore = async (message: MailboxMessage) => {
    if (!searched || restoring) return;
    setRestoring(message.id);
    setRowErrors(previous => ({ ...previous, [message.id]: "" }));
    try {
      const result = await restoreMailboxMessage(searched.account, message.id);
      setMessages(previous => previous.map(item => item.id === message.id
        ? { ...item, conversationId: result.conversationId, archived: item.archived && !result.movedToInbox } : item));
      if (!result.movedToInbox) {
        try { toast.info(t("crm.mailbox.keptArchived")); } catch { /* An older host without toasts: the dialog said so up front. */ }
      }
      onOpenConversation(result.conversationId);
    } catch (restoreError) {
      setRowErrors(previous => ({ ...previous, [message.id]: failure(restoreError, t("crm.mailbox.restoreFailed")) }));
    } finally {
      setRestoring(null);
    }
  };

  return <DialogShell open={open} onOpenChange={onOpenChange} size="lg" icon={<MailSearch className="size-5" aria-hidden="true" />}
    title={t("crm.mailbox.find")} description={t("crm.mailbox.description")}>
    <div className="flex flex-col gap-3">
      <form role="search" className="grid gap-3 sm:grid-cols-[minmax(0,14rem)_minmax(0,1fr)_auto] sm:items-end"
        onSubmit={(event: { preventDefault: () => void }) => { event.preventDefault(); void run({ account, query }); }}>
        <div className="grid min-w-0 gap-1.5">
          <Label htmlFor={accountId} className="text-xs text-muted-foreground">{t("crm.mailbox.account")}</Label>
          <Select value={account} onValueChange={(value: string) => setAccount(value)} disabled={accounts.length < 2}>
            <SelectTrigger id={accountId} className="w-full min-w-0"><SelectValue>{chosen?.label ?? account}</SelectValue></SelectTrigger>
            <SelectContent>{accounts.map(item => <SelectItem key={item.address} value={item.address}>
              {item.label === item.address ? item.address : `${item.label} · ${item.address}`}
            </SelectItem>)}</SelectContent>
          </Select>
        </div>
        <div className="grid min-w-0 gap-1.5">
          <Label htmlFor={queryId} className="text-xs text-muted-foreground">{t("crm.mailbox.query")}</Label>
          <Input id={queryId} type="search" autoFocus value={query} aria-describedby={hintId} autoComplete="off" spellCheck={false}
            placeholder={t("crm.mailbox.placeholder")} onChange={(event: { target: { value: string } }) => setQuery(event.target.value)} />
        </div>
        <Button type="submit" disabled={!account || !query.trim() || loading}>
          <Search className="size-4" aria-hidden="true" />{t("crm.mailbox.search")}
        </Button>
      </form>
      <p id={hintId} className="text-xs text-muted-foreground">{t("crm.mailbox.hint")}</p>
      {chosen && !chosen.canModify && <p role="note" className="rounded-field border border-border bg-muted/50 px-3 py-2 text-xs text-muted-foreground">{t("crm.mailbox.readOnly")}</p>}

      {loading ? <p role="status" className="flex items-center justify-center gap-2 py-8 text-sm text-muted-foreground">
        <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />{t("crm.mailbox.searching")}
      </p> : error ? <div role="alert" className="flex flex-wrap items-center justify-between gap-2 rounded-field border border-destructive/40 px-3 py-2 text-sm text-destructive">
        <span className="min-w-0">{error}</span>
        {searched && <Button type="button" size="toolbar" variant="subtle" onClick={() => void run(searched)}>{t("crm.mailbox.retry")}</Button>}
      </div> : !searched ? <p className="py-8 text-center text-sm text-muted-foreground">{t("crm.mailbox.start")}</p>
      : !messages.length ? <p role="status" className="py-8 text-center text-sm text-muted-foreground">{t("crm.mailbox.empty")}</p>
      : <ul aria-label={t("crm.mailbox.results")} className="divide-y divide-border rounded-field border border-border">
        {messages.map(message => <li key={message.id} className="grid gap-2 px-3 py-2.5 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
          <div className="min-w-0">
            <div className="flex min-w-0 items-baseline gap-2">
              <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{message.from}</span>
              {message.archived && <Badge variant="secondary" className="shrink-0 px-1.5 py-0 text-[11px] font-normal">{t("crm.mailbox.archived")}</Badge>}
              <time className="shrink-0 text-xs tabular-nums text-muted-foreground">{messageDate(message.date)}</time>
            </div>
            <p className="truncate text-sm text-foreground">{message.subject || t("crm.mailbox.noSubject")}</p>
            {message.snippet && <p className="line-clamp-2 text-xs text-muted-foreground">{message.snippet}</p>}
            {rowErrors[message.id] && <p role="alert" className="mt-1 text-xs text-destructive">{rowErrors[message.id]}</p>}
          </div>
          <div className="flex justify-end">
            {message.conversationId
              ? <Button type="button" size="toolbar" variant="subtle" onClick={() => onOpenConversation(message.conversationId!)}>{t("crm.mailbox.open")}</Button>
              : <Button type="button" size="toolbar" variant="subtle" disabled={!!restoring} onClick={() => void restore(message)}>
                {restoring === message.id ? t("crm.mailbox.restoring") : t("crm.mailbox.restore")}
              </Button>}
          </div>
        </li>)}
      </ul>}
      {!loading && !error && nextPage && searched && <div className="flex flex-col items-center gap-2">
        {moreError && <p role="alert" className="text-xs text-destructive">{moreError}</p>}
        <Button type="button" size="toolbar" variant="ghost" disabled={loadingMore} onClick={() => void run(searched, nextPage)}>
          {loadingMore ? t("crm.mailbox.loadingMore") : moreError ? t("crm.mailbox.retry") : t("crm.mailbox.loadMore")}
        </Button>
      </div>}
    </div>
  </DialogShell>;
}
