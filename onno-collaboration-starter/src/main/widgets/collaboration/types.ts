export interface UiEvent {
  type: string;
  entityType?: string;
  entityName?: string;
  id?: string;
  timestamp?: string;
  kind?: string;
  viewers?: { userId: string; displayName: string; avatarUrl?: string }[];
  notificationType?: string;
  title?: string;
  body?: string;
  link?: string;
  actorName?: string;
  actorAvatar?: string;
  createdAt?: string;
  unread?: boolean;
}
