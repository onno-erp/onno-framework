# onno 4.0.0

Connected accounts, personal inboxes and typed page views. Teams can connect several Gmail
mailboxes and Telegram business accounts, shared or personal, and pages compose reusable typed
views instead of hand-written widget JSON. This is a breaking release: read
[Upgrade requirements](#upgrade-requirements) before bumping.

## Connected accounts

- New `CrmAccountProvider` SPI and `CrmConnectionAccounts` service: each provider account is
  `SHARED` (managed by administrators) or `PERSONAL` (owned by one user). Credentials stay in the
  provider; the CRM stores ownership, label and the bound inbox only.
- `GET/POST /api/crm/connection-accounts` and `POST /api/crm/connection-accounts/{id}` with typed
  responses (`Listing`, `AccountView`). The bundled connections page is the `crmConnections`
  widget (`crmSettings` still works), authored with `CrmConnectionsView`.
- **Personal accounts are opt-in**: `CrmFeatures.defaults().withPersonalAccounts(true)`. A personal
  inbox's conversations, contact identities and email addresses are visible only to its owner —
  not to colleagues, not to administrators. Owners always keep control of their own accounts.
- Gmail: several shared and personal mailboxes, imported attachments, and replies sent from Gmail
  on known threads. Telegram: business accounts (reply from a person's own Telegram account) next
  to the ordinary bot inbox.
- Email replies can override subject and recipient; messages may carry only attachments.

## Typed page views

- `PageView` and `PageBuilder.view(...)` place reusable content; `ListView` packages list defaults.
- `CrmInboxView` replaces the `crmInboxWorkspaces` JSON config: one method per surface and screen
  size (`header(...)`, `header(Breakpoint.MOBILE, ...)`, `listHeader`, `composer`). Each toolbar
  is a full replacement, views freeze once placed, and startup fails on an unknown workspace or
  filter key. A contract test keeps the Java keys and the React renderer in step.
- `CrmInboxWorkspace.builder(key, label)`; facets with `FacetResolver`, options computed per
  request (`FacetOptions`) and optional coloured chips on rows (`showOnRows`).
- `EntityView.opensInMain()` opens records that are full working surfaces in the main pane.

## UI and widget SDK

- Inbox filter sidebar, channel and account navigation, voice message player, linked text,
  responsive workspace toolbars (`fit`, `narrowLayout`), mobile page headers.
- SDK: `ColorPicker`, `DialogShell` (typed props), `OnnoReadApi.getJson` (same-origin `/api/` reads;
  results are copies), `ListRendererOptions.ownsLoadingMore`, slot `crm.contact.profile`.
- Inline tag creation from the tag picker (`POST /api/tags/{kind}/{name}`), guarded by the tag
  catalog's write access and refused for record-restricted subjects.
- `GET /api/media/limits`; audio served inline; original filenames kept and sent as UTF-8.

## Reliability and security fixes

- A Telegram update that keeps failing is skipped after three attempts instead of stalling all
  intake; file downloads happen outside the CRM-wide lock; transient pre-send failures retry.
- Inbound attachments respect `onno.media.max-file-size` and `onno.media.allowed-content-types`;
  refused files are skipped with a note instead of blocking the message.
- Gmail no longer re-imports attachments for newly connected mailboxes.
- Plugins load only after sign-in (they are still preloaded).
- `docs/CONFIGURATION.md` regenerates when only property Javadoc changes.

## Breaking changes

- **Generic CRM catalogs and administrators.** With personal accounts enabled, `CrmConversations`,
  `CrmConversationMessages`, `CrmContactIdentities` and `CrmInboxes` are closed to ADMIN on generic
  REST, UI, export, MCP and event surfaces; use the scoped CRM APIs. `CrmInboxes` is now protected
  from non-administrators in every configuration. (Record access policies cannot express this:
  administrators bypass them.)
- **`CrmInboxWorkspaceService`** requires `CrmConnectionAccounts` in its constructor; the 5-argument
  constructor is gone. Per-request facets no longer need a subclass — declare a `Facet` with
  `FacetOptions`.
- **Records gained components**: `CrmInboxWorkspace` (`facets`), `CrmMessageTransport.Connection`
  (`recipient`, `recipients`), `ConversationService.Reply` (`subject`, `recipient`),
  `CrmInboxController.SendMessage`, `TelegramClient.Bot` / `Update`. Old constructors remain; record
  patterns and canonical constructors change.
- **Channel adapter constructors**: `GmailBridge`, `GmailOAuthController` and `TelegramInboxBridge`
  take their collaborators by constructor (no field or setter injection).
- **`ConversationMessage.body`** is optional (attachment-only messages); new `subject` and
  `recipient` columns. Schema reconciliation applies this automatically.
- **`onno.crm.channels.telegram.regular-chats-enabled=false`** now ignores ordinary bot chats
  entirely (a business-only bot) instead of only starting the inbox paused.
- **Paused ordinary Telegram inbox** keeps polling for business chats; ordinary messages arriving
  while paused are discarded.
- **`UiEntityAccessPolicy.includesAdministrators()`** lets a policy bind ADMIN; existing policies are
  unaffected.
- **Inbound attachment limit** follows `onno.media.max-file-size` (default 10 MB) instead of a fixed
  20 MB.
- **Visual**: inline option facets are a vertical list; facets follow the configured filter order;
  the mobile back button lives in the page header.

## Upgrade requirements

1. Bump `su.onno` (and `su.onno.enterprise`, if used) to 4.0.0.
2. If you subclass `CrmInboxWorkspaceService`, pass `CrmConnectionAccounts` to `super(...)`, and
   move per-request facet logic into `Facet`s with `FacetOptions`.
3. To offer personal accounts, register `CrmFeatures.defaults().withPersonalAccounts(true)`, and
   override `CrmChannelAccess.canManagePersonal` if not every signed-in user may connect one.
   Tell administrators that generic CRM catalogs close to them once this is on.
4. Replace `p.widget(...).type("crmInboxWorkspaces").config(...)` with a `CrmInboxView` bean and
   `p.view(inbox)`; the old config keys keep working.
5. Custom channel adapters that support personal accounts must bind the inbox with
   `CrmConnectionAccounts.bindInbox` and use the account's inbox UUID as the contact identity
   `connectionKey` (see the CRM starter README).
6. Custom list renderers that draw their own pagination indicator should set
   `ownsLoadingMore: true`.
