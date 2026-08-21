import { Badge, Card, CardContent, registerWidget, type WidgetProps } from "@onno/widget-sdk";

type Conversation = {
  channel: "Email" | "Phone" | "Chat";
  subject: string;
  preview: string;
  when: string;
};

/** Sample conversations used by the example app until it grows a communications catalog. */
function conversationsFor(name: string): Conversation[] {
  return [
    {
      channel: "Email",
      subject: "New releases recommendation",
      preview: `${name} asked for literary-fiction suggestions and a signed-edition alert.`,
      when: "Today · 09:42",
    },
    {
      channel: "Phone",
      subject: "Delivery follow-up",
      preview: "Confirmed the latest order arrived safely; no replacement was needed.",
      when: "Tue · 15:10",
    },
    {
      channel: "Chat",
      subject: "Reading list",
      preview: "Shared a shortlist of three titles and saved one for the next store visit.",
      when: "May 18 · 11:26",
    },
  ];
}

/** Lucide's Astroid icon, kept inline so the widget remains dependency-free at runtime. */
function AstroidIcon({ className = "" }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      <path d="M12.983 21.186a1 1 0 0 1-1.966 0 10 10 0 0 0-8.203-8.203 1 1 0 0 1 0-1.966 10 10 0 0 0 8.203-8.203 1 1 0 0 1 1.966 0 10 10 0 0 0 8.203 8.203 1 1 0 0 1 0 1.966 10 10 0 0 0-8.203 8.203" />
    </svg>
  );
}

function CustomerConversations({ widget }: WidgetProps) {
  const record = widget.record;
  if (!record) return null;

  const name = String(record.data.description ?? "This client");
  const conversations = conversationsFor(name);

  return (
    <div className="space-y-3">
      <Card>
        <CardContent className="p-0">
          <div className="border-b border-border px-5 py-4">
            <div className="text-sm font-semibold text-foreground">{widget.title}</div>
            <div className="mt-0.5 text-xs text-muted-foreground">Recent touchpoints with {name}</div>
          </div>
          <ol className="divide-y divide-border">
            {conversations.map((conversation) => (
              <li key={`${conversation.channel}:${conversation.subject}`} className="px-5 py-4">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <Badge variant="secondary">{conversation.channel}</Badge>
                      <span className="truncate text-sm font-medium text-foreground">
                        {conversation.subject}
                      </span>
                    </div>
                    <p className="mt-1.5 text-xs leading-5 text-muted-foreground">
                      {conversation.preview}
                    </p>
                  </div>
                  <time className="shrink-0 text-[11px] tabular-nums text-muted-foreground">
                    {conversation.when}
                  </time>
                </div>
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>

      <Card className="border-primary/20">
        <CardContent className="p-0">
          <div className="flex items-center justify-between gap-3 px-5 pt-4">
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex size-8 shrink-0 items-center justify-center rounded-field bg-primary/10 text-primary">
                <AstroidIcon className="size-4" />
              </div>
              <div className="text-sm font-semibold text-foreground">AI summary</div>
            </div>
            <Badge variant="secondary">Positive</Badge>
          </div>
          <div className="px-5 pb-4 pt-3">
            <p className="text-sm leading-6 text-foreground">
              {name} is an engaged repeat client who responds well to personal recommendations. Their
              latest conversations center on literary fiction and special editions; follow up when a
              matching signed title becomes available.
            </p>
            <div className="mt-4 grid grid-cols-2 gap-4 border-t border-border pt-3 text-xs">
              <div>
                <div className="text-muted-foreground">Next best action</div>
                <div className="mt-0.5 font-medium text-foreground">Send curated shortlist</div>
              </div>
              <div>
                <div className="text-muted-foreground">Relationship signal</div>
                <div className="mt-0.5 font-medium text-foreground">High engagement</div>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

registerWidget("customerConversations", CustomerConversations);
