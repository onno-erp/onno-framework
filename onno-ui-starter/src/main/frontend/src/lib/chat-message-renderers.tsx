import { Component, useSyncExternalStore, type ReactNode } from "react";

/** Read-only message data supplied by the CRM timeline. */
export interface ChatMessage {
  id: string;
  kind: "CUSTOMER_MESSAGE" | "AGENT_REPLY" | "SYSTEM_EVENT";
  direction: "INBOUND" | "OUTBOUND" | "INTERNAL";
  channel: string;
  authorName: string;
  body: string;
  sentAt: string;
  deliveryStatus: string;
}
export interface ChatMessageRendererProps {
  message: Readonly<ChatMessage>;
  fallback: import("react").ReactNode;
}
export interface ChatMessageRenderer {
  /** Unique namespaced ID; registering it again replaces the renderer. */
  id: string;
  /** Higher priority wins; equal priorities resolve by ID for stable load order. */
  priority?: number;
  matches: (message: Readonly<ChatMessage>) => boolean;
  component: import("react").ComponentType<ChatMessageRendererProps>;
}

const renderers = new Map<string, ChatMessageRenderer>();
const listeners = new Set<() => void>();
let version = 0;
function changed() { version++; listeners.forEach(listener => listener()); }
function subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
function snapshot() { return version; }

export function registerChatMessageRenderer(renderer: ChatMessageRenderer): () => void {
  if (!renderer.id.trim()) throw new Error("Chat renderer ID is required");
  const entry = { ...renderer };
  renderers.set(entry.id, entry);
  changed();
  return () => {
    if (renderers.get(entry.id) === entry) { renderers.delete(entry.id); changed(); }
  };
}

class RendererBoundary extends Component<{ children: ReactNode; fallback: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}

export function ChatMessageBody({ message, fallback }: ChatMessageRendererProps) {
  const revision = useSyncExternalStore(subscribe, snapshot, snapshot);
  const renderer = [...renderers.values()]
    .sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0) || a.id.localeCompare(b.id))
    .find(entry => {
      try { return entry.matches(message); } catch { return false; }
    });
  if (!renderer) return <>{fallback}</>;
  const Body = renderer.component;
  return <RendererBoundary key={`${message.id}:${message.body}:${revision}`} fallback={fallback}>
    <Body message={message} fallback={fallback} />
  </RendererBoundary>;
}

export const chatMessages = Object.freeze({ register: registerChatMessageRenderer, Body: ChatMessageBody });
