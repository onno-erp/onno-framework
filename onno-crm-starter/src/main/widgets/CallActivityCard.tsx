import { text as t } from "@onno/widget-sdk";
import { Button, CommentBody, useState } from "@onno/widget-sdk";
import { Phone, Video, ExternalLink } from "lucide-react";

/** Reads the existing internal-event format, including calls recorded before cards were introduced. */
export function callActivity(body: string) {
  const [heading, ...lines] = body.split("\n");
  const [type, scheduled] = heading.split(" · Scheduled for ");
  const titles: Record<string,string> = {
    "Call completed":t("crm.activity.callCompleted"), "Call planned":t("crm.activity.callPlanned"),
    "Meeting planned":t("crm.activity.meetingPlanned"), "Activity":t("crm.activity.other"),
  };
  if (!titles[type]) return null;
  let recording: string | undefined;
  const last = lines.at(-1);
  if (last?.startsWith("Recording: ")) {
    try {
      const url = new URL(last.slice("Recording: ".length));
      if (url.protocol === "https:" && !url.username && !url.password) {recording=url.href;lines.pop();}
    } catch { /* Keep an invalid legacy link visible as text in the summary. */ }
  }
  return {title:titles[type], scheduled, recording, summary:lines.join("\n"), planned:type.endsWith("planned"), meeting:type === "Activity" || type === "Meeting planned"};
}

export function CallActivityCard({activity,author,at}: {activity:NonNullable<ReturnType<typeof callActivity>>;author:string;at?:string}) {
  const [expanded,setExpanded]=useState(false);
  const long=activity.summary.length>360;
  const Icon=activity.meeting?Video:Phone;
  return <article aria-label={activity.title} className="mx-auto w-full max-w-lg overflow-hidden rounded-panel border border-border bg-card text-foreground shadow-sm">
    <div className="flex items-center gap-3 border-b border-border/60 px-4 py-3">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-field bg-primary/10 text-primary"><Icon className="size-4" /></span>
      <div className="min-w-0 flex-1"><h3 className="text-sm font-semibold">{activity.title}</h3><p className="mt-0.5 text-[11px] text-muted-foreground">{author}{at ? ` · ${at}` : ""}</p></div>
      <span className="rounded-pill bg-muted px-2 py-1 text-[10px] text-muted-foreground">{activity.planned?t("crm.activity.planned"):t("crm.activity.logged")}</span>
    </div>
    <div className="space-y-2 px-4 py-3">
      {activity.scheduled&&<p className="text-xs font-medium">{activity.scheduled}</p>}
      <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">{t("crm.activity.summary")}</p>
      <div className="whitespace-pre-wrap break-words text-[13px] leading-5"><CommentBody body={long&&!expanded?`${activity.summary.slice(0,360)}…`:activity.summary} /></div>
      {long&&<Button size="sm" variant="ghost" aria-expanded={expanded} onClick={()=>setExpanded(value=>!value)}>{expanded?t("crm.activity.showLess"):t("crm.activity.showMore")}</Button>}
    </div>
    <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border/60 px-4 py-2.5">
      <span className="text-[10px] text-muted-foreground">{t("crm.activity.internal")}</span>
      {activity.recording&&<a href={activity.recording} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 rounded-field px-2 py-1 text-xs font-medium text-primary hover:bg-primary/10"><ExternalLink className="size-3.5" />{t("crm.activity.openRecording")}</a>}
    </div>
  </article>;
}
