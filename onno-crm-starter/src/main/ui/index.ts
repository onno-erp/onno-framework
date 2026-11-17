/**
 * `@onno/crm` — the CRM's screens as components. Each is rendered directly by the app; the server
 * sends data (a workspace's conversations and behaviour), never a component name.
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
