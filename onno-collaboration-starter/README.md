# onno-collaboration-starter

Opt-in collaboration feature pack for the onno UI:

- entity comments, replies, reactions, and `@`/`#` mentions;
- per-user notifications and built-in mention/reply/assignment producers;
- live presence in navigation, tabs, and entity-list rows;
- the complete React UI for those features.

Adding the dependency is enough. The starter contributes its Spring APIs and an
`onno-plugins/Collaboration.js` browser module; `onno-ui-starter` discovers and loads that module
automatically. React and the design-system primitives are shared with the host SPA, not bundled a
second time.

```kotlin
implementation("su.onno:onno-ui-starter:<version>")
implementation("su.onno:onno-collaboration-starter:<version>")
```

The feature pack is enabled by default when present. Remove the dependency for a UI/server build
with no collaboration code, or disable it without changing dependencies:

```yaml
onno:
  collaboration:
    enabled: false
```

Existing fine-grained switches remain compatible: `onno.comments.*` and
`onno.notifications.*`. Comments remain opt-in per entity through `EntityView.comments()`.
Public Java packages such as `su.onno.ui.notifications.NotificationService` and `AssigneeField`
keep their existing names; only their Maven artifact moved.

The pack uses the generic UI extension contracts in `onno-ui-starter`:
`EntityDetailUiContributor`, `ShellUiContributor`, and the browser-side `registerUiFeature`.
Third-party feature packs can use the same seam to add server-authored DivKit blocks and React
roots/adornments/actions without patching the host SPA.
