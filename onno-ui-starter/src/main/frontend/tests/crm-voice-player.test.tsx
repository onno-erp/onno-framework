import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
vi.mock("@onno/widget-sdk", async importOriginal => { await import("@/lib/plugin-host"); return importOriginal(); });
import { VoicePlayer } from "../../../../../onno-crm-starter/src/main/widgets/VoicePlayer";
afterEach(()=>{cleanup();vi.restoreAllMocks();});
it("controls playback, seeking and speed through the themed controls",async()=>{
  render(<VoicePlayer src="/voice.ogg" label="Voice" download="voice.ogg"/>);
  const audio=screen.getByLabelText("Voice") as HTMLAudioElement;
  vi.spyOn(audio,"play").mockImplementation(async()=>{fireEvent.play(audio);});
  fireEvent.click(screen.getByRole("button",{name:"Play voice message"}));
  expect(await screen.findByRole("button",{name:"Pause voice message"})).toBeInTheDocument();
  Object.defineProperty(audio,"duration",{value:60});fireEvent.durationChange(audio);
  fireEvent.change(screen.getByRole("slider"),{target:{value:"25"}});
  expect(audio.currentTime).toBe(25);
  expect(screen.getByRole("slider")).toHaveAttribute("aria-valuetext","0:25 / 1:00");
  fireEvent.click(screen.getByRole("button",{name:"Playback speed: 1×"}));
  expect(audio.playbackRate).toBe(1.5);
  fireEvent.ended(audio);
  expect(screen.getByRole("button",{name:"Play voice message"})).toBeInTheDocument();
});
it("keeps the download fallback when playback fails",async()=>{
  render(<VoicePlayer src="/voice.ogg" label="Voice" download="voice.ogg"/>);
  const audio=screen.getByLabelText("Voice") as HTMLAudioElement;
  vi.spyOn(audio,"play").mockRejectedValue(new Error("Unsupported codec"));
  fireEvent.click(screen.getByRole("button",{name:"Play voice message"}));
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("Try downloading"));
  expect(screen.getByRole("link",{name:"voice.ogg"})).toHaveAttribute("href","/voice.ogg");
});
