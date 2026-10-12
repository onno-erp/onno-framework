import { React, Button, useRef, useState, text as t } from "@onno/widget-sdk";
import { Download, Pause, Play } from "lucide-react";

function time(seconds: number) {
  const value = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, "0")}`;
}

/** The themed player for audio attachments in the shared inbox. */
export function VoicePlayer({ src, label, download, outbound = false }: {
  src: string; label: string; download?: string; outbound?: boolean;
}) {
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [speed, setSpeed] = useState(1);
  const [failed, setFailed] = useState(false);
  const toggle = async () => {
    if (!audio.current) return;
    if (!audio.current.paused) { audio.current.pause(); return; }
    try { await audio.current.play(); setFailed(false); }
    catch { setFailed(true); }
  };
  return <div className={`w-64 max-w-full min-w-0 rounded-field p-2 ${outbound ? "bg-primary-foreground/10 text-primary-foreground" : "bg-muted/60 text-foreground"}`}>
    <audio ref={audio} src={src} preload="none" aria-label={label} className="hidden"
      onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)}
      onEnded={() => setPlaying(false)} onError={() => { setFailed(true); setPlaying(false); }}
      onTimeUpdate={event => setPosition(event.currentTarget.currentTime)}
      onDurationChange={event => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)} />
    <div className="flex items-center gap-2">
      <Button type="button" variant="ghost" className={`size-10 shrink-0 rounded-pill p-0 ${outbound ? "bg-primary-foreground/15 text-inherit hover:bg-primary-foreground/25 hover:text-inherit" : "bg-background text-foreground hover:bg-background/70"}`}
        aria-label={t(playing ? "crm.voice.pause" : "crm.voice.play")} onClick={() => void toggle()}>
        {playing ? <Pause className="size-4" aria-hidden="true" /> : <Play className="size-4" aria-hidden="true" />}
      </Button>
      <div className="min-w-0 flex-1">
        <input type="range" min={0} max={duration || 1} step={0.1} value={Math.min(position, duration || 1)} disabled={!duration}
          aria-label={t("crm.voice.seek")} aria-valuetext={`${time(position)} / ${time(duration)}`}
          className="block h-5 w-full cursor-pointer accent-current focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current disabled:cursor-default"
          onChange={event => { const value = Number(event.target.value); if (audio.current) audio.current.currentTime = value; setPosition(value); }} />
        <div className="flex items-center justify-between text-xs tabular-nums opacity-70">
          <span>{time(position)}{duration > 0 ? ` / ${time(duration)}` : ""}</span>
          <button type="button" className="rounded-pill px-1 font-medium focus-visible:outline focus-visible:outline-2 focus-visible:outline-current" aria-label={t("crm.voice.speed", {speed})}
            onClick={() => { const next = speed === 2 ? 1 : speed + 0.5; setSpeed(next); if (audio.current) audio.current.playbackRate = next; }}>{speed}×</button>
        </div>
      </div>
      {download && <a href={src} download={download} target="_blank" rel="noreferrer" aria-label={download} title={download}
        className="flex size-8 shrink-0 items-center justify-center rounded-pill opacity-70 hover:opacity-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-current"><Download className="size-4" aria-hidden="true" /></a>}
    </div>
    {failed && <p role="alert" className="mt-1 text-xs">{t("crm.voice.playbackError")}</p>}
  </div>;
}
