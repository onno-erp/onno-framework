/**
 * `@onno/crm` — the CRM's screens as components. Each is rendered directly by the app; the server
 * sends data (a workspace's conversations and behaviour), never a component name.
 *
 * Three layers, so an app can take as much as it wants and rebuild the rest:
 * - screens: `CrmInbox`, `CrmConversation` (one chat anywhere; `openCrmConversation` docks it in a
 *   window), `CrmContactPage`, `CrmConnections`;
 * - parts: the row, the timeline and its entries, the composer and its fields, the contact panel;
 * - hooks: `useCrmConversation` (timeline, delivery, commands), `useCrmComposer` (draft, files,
 *   send), `useCrmContact`, `useCrmWorkspaceConversation`.
 *
 *   <CrmInbox workspace="support" list={{ columns: ["customer", "subject", "lastMessageAt"] }} />
 *   <CrmConnections setups={[channelSetups]} />
 *   recordPanels: [{ component: CrmContactDetails, title: "Contact" }]
 */
export {
  CrmInbox,
  type CrmInboxProps, type CrmInboxListOptions, type CrmInboxToolbar, type CrmInboxLayouts, type ConversationField,
} from "./CrmInbox";
export { CrmConversationPage } from "./CrmConversationPage";
export { CrmConnections, type CrmConnectionsProps, type CrmConnectionSetup, type CrmConnectionSetupProps, type CrmConnectionAccount } from "./CrmConnections";
export {
  CrmContactDetails,
  type CrmPresentation, type CrmContactField, type CrmFieldFormat, type CrmAction, type CrmIcon, type CrmFolderPresentation,
} from "./CrmWorkspace";
export type { ConversationRowOptions } from "./ConversationList";
export type { ChatMessage, ChatMessageRenderer, ChatMessageRendererProps } from "./chatMessageRenderers";

// Screens built from the parts below.
export { CrmConversation, ConversationBody as CrmConversationBody, openCrmConversation, useCrmWorkspaceConversation, useConversationExtensionContext,
  type CrmConversationProps, type CrmConversationComponents, type CrmConversationPartProps } from "./CrmConversation";
export { CrmContactPage, CrmContactRoute, useCrmContact,
  type CrmContact, type CrmContactPageProps, type CrmContactPageComponents, type CrmContactPagePartProps } from "./CrmContactPage";
export type { CrmInboxComponents, InboxContactPanelProps } from "./CrmInbox";

// Parts.
export { ConversationRow, type ConversationRowProps, type ConversationRowComponent, type ConversationRowMenu } from "./ConversationRow";
export { ContactRowMenu as ConversationMenu } from "./ContactRowMenu";
export { ConversationList, ConversationRowContent, ConversationListSkeleton } from "./ConversationList";
export { ChatTimeline, TimelineMessage, TimelineSystemEvent, TimelineComment, MessageAttachments, MessageRecipients, CrmAvatar, ChannelIcon,
  type ChatTimelineProps, type MessageActionsContext } from "./ChatTimeline";
export { CrmComposer, CrmComposerFields, SenderControls, CcField, AttachButton, FileDropOverlay,
  type CrmReplyInputProps, type CrmComposerProps, type CrmComposerPartProps, type CrmComposerLayouts, type CrmAttachProps, type CrmComposerFieldParts } from "./CrmComposer";
export { composerWindows, type ComposerWindow } from "./composerWindows";
export { ContactPanel as CrmContactPanel, CrmPresentationProvider, useCrmPresentation, type Config as CrmConfig } from "./CrmWorkspace";
export { ChannelLogo } from "./ChannelLogo";
export { ChatMessageBody, ChatMessageRenderers } from "./chatMessageRenderers";

// Hooks and data.
export { useCrmConversation, useTimelineWithSources, type CrmConversationState, type CrmTimelineSource, type CrmActivityEntry, type UseCrmConversationOptions } from "./useCrmConversation";
export { useCrmComposer, type CrmComposerState } from "./useCrmComposer";
export type { CrmMessage, CrmNote, CrmAttachment, CrmDelivery, CrmTimelineEntry, CrmConversationAction } from "./crmModel";
export { splitAddresses, parseAddress, addressLabel } from "./emailAddresses";
