import { Component, createContext, useContext, type ComponentType, type ReactNode } from "react";

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
  /** What the timeline would draw without this renderer. */
  fallback: ReactNode;
}
/**
 * A component that draws some messages its own way (a booking card for a provider's structured
 * message, say), passed to `<CrmInbox messageRenderers={[…]} />`.
 */
export interface ChatMessageRenderer {
  /** Unique namespaced ID: the tie-breaker for equal priorities. */
  id: string;
  /** Higher priority wins. */
  priority?: number;
  matches: (message: Readonly<ChatMessage>) => boolean;
  component: ComponentType<ChatMessageRendererProps>;
}

const Renderers = createContext<readonly ChatMessageRenderer[]>([]);

/** Hands the inbox's message renderers to the timeline below. */
export function ChatMessageRenderers({ renderers, children }: { renderers?: readonly ChatMessageRenderer[]; children: ReactNode }) {
  return <Renderers.Provider value={renderers ?? []}>{children}</Renderers.Provider>;
}

class RendererBoundary extends Component<{ children: ReactNode; fallback: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}

/**
 * One message's body: the highest-priority renderer that matches it, else the fallback. A
 * renderer that throws (in its predicate or while rendering) degrades to the fallback.
 */
export function ChatMessageBody({ message, fallback }: ChatMessageRendererProps) {
  const renderers = useContext(Renderers);
  const renderer = [...renderers]
    .sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0) || a.id.localeCompare(b.id))
    .find(entry => {
      try { return entry.matches(message); } catch { return false; }
    });
  if (!renderer) return <>{fallback}</>;
  const Body = renderer.component;
  return <RendererBoundary key={`${message.id}:${message.body}:${renderer.id}`} fallback={fallback}>
    <Body message={message} fallback={fallback} />
  </RendererBoundary>;
}
