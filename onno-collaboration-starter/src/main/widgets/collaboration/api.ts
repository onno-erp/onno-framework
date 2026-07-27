import { request } from "@onno/widget-sdk";

export interface CommentMention {
  id: string;
  kind: "catalogs" | "documents";
  name: string;
  entity: string | null;
  display: string | null;
  avatarUrl: string | null;
  readable: boolean;
}

export interface MentionSuggestion {
  kind: "catalogs" | "documents";
  name: string;
  entity: string;
  id: string;
  display: string;
  avatarUrl: string | null;
  hint?: string | null;
}

export interface ResolvedMentionTarget {
  kind: "catalogs" | "documents";
  name: string;
  entity: string | null;
  id: string;
  display: string | null;
  avatarUrl: string | null;
  readable: boolean;
  person?: boolean;
}

export interface CommentReaction {
  emoji: string;
  count: number;
  mine: boolean;
}

export interface CommentView {
  id: string;
  authorName: string | null;
  authorAvatarUrl: string | null;
  body: string;
  parentId: string | null;
  mentions: CommentMention[];
  reactions: CommentReaction[];
  createdAt: string | null;
  editedAt: string | null;
  mine: boolean;
  canDelete: boolean;
}

export interface PresenceViewer {
  userId: string;
  displayName: string;
  avatarUrl?: string;
}

export interface PresenceState {
  you: string;
  viewers: PresenceViewer[];
}

export interface PresenceSnapshot {
  you: string;
  records: Array<{
    kind: string;
    name: string;
    id: string;
    viewers: PresenceViewer[];
  }>;
}

export interface NotificationView {
  id: string;
  type: string;
  title: string;
  body?: string | null;
  link?: string | null;
  actorName?: string | null;
  actorAvatar?: string | null;
  createdAt: string;
  readAt?: string | null;
  unread: boolean;
}

export interface NotificationPage {
  items: NotificationView[];
  nextCursor: string | null;
  hasMore: boolean;
  unreadCount: number;
  types: string[];
}

const BASE = "/api";

export const api = {
  listComments: (kind: "catalogs" | "documents", name: string, id: string) =>
    request<CommentView[]>(`${BASE}/comments/${kind}/${name}/${id}`),
  addComment: (
    kind: "catalogs" | "documents",
    name: string,
    id: string,
    body: string,
    parentId?: string | null
  ) =>
    request<CommentView>(`${BASE}/comments/${kind}/${name}/${id}`, {
      method: "POST",
      body: JSON.stringify({ body, parentId: parentId ?? null }),
    }),
  deleteComment: (commentId: string) =>
    request<void>(`${BASE}/comments/${commentId}`, { method: "DELETE" }),
  toggleCommentReaction: (commentId: string, emoji: string) =>
    request<CommentReaction[]>(`${BASE}/comments/${commentId}/reactions`, {
      method: "POST",
      body: JSON.stringify({ emoji }),
    }),
  searchMentions: (q: string, kind?: "people" | "catalogs" | "documents") => {
    const params = new URLSearchParams({ q });
    if (kind) params.set("kind", kind);
    return request<MentionSuggestion[]>(`${BASE}/mentions?${params}`);
  },
  resolveMention: (kind: "catalogs" | "documents", name: string, id: string) =>
    request<ResolvedMentionTarget>(
      `${BASE}/mentions/resolve?${new URLSearchParams({ kind, name, id })}`
    ),
  getNotifications: (opts?: { unread?: boolean; cursor?: string }) => {
    const params = new URLSearchParams();
    if (opts?.unread) params.set("unread", "true");
    if (opts?.cursor) params.set("cursor", opts.cursor);
    const qs = params.toString();
    return request<NotificationPage>(
      `${BASE}/notifications${qs ? `?${qs}` : ""}`
    );
  },
  markNotificationRead: (id: string) =>
    request<{ unreadCount: number }>(`${BASE}/notifications/${id}/read`, {
      method: "POST",
    }),
  markAllNotificationsRead: () =>
    request<{ marked: number; unreadCount: number }>(
      `${BASE}/notifications/read-all`,
      { method: "POST" }
    ),
  getPresenceSnapshot: () => request<PresenceSnapshot>(`${BASE}/presence`),
  presence: (path: string, action: "enter" | "heartbeat") =>
    request<PresenceState>(`${BASE}/presence`, {
      method: "POST",
      body: JSON.stringify({ path, action }),
    }),
  leavePresence: (path: string) =>
    request<PresenceState>(`${BASE}/presence`, {
      method: "POST",
      body: JSON.stringify({ path, action: "leave" }),
      keepalive: true,
    }),
};
