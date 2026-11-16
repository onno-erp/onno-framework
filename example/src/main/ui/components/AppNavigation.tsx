import { DefaultNavigation, Navigation, type NavigationProps } from "@onno/ui";

/** A custom layout can mix the resolved tree with any React components. */
export function AppNavigation({ navigation, header, footer }: NavigationProps) {
  return (
    <Navigation>
      {header}
      <DefaultNavigation sections={navigation} />
      {footer}
    </Navigation>
  );
}
