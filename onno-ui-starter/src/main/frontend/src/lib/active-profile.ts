/**
 * The shell's active UI profile (persona) id, for components that call the server outside a
 * DivKit surface and must resolve the same EntityView, e.g. the reference picker dialog (#411).
 * The shell writes it whenever the profile changes; null means the caller's default profile.
 */
let activeProfile: string | null = null;

export function setActiveProfile(profile: string | null): void {
  activeProfile = profile;
}

export function getActiveProfile(): string | null {
  return activeProfile;
}
