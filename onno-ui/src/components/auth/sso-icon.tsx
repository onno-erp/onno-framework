/**
 * A provider brand logo for an SSO button. By default the logo is rendered as-is (an {@code <img>}),
 * keeping its own brand colors — e.g. a full-color badge. When {@code monochrome} is set, the SVG is
 * instead used as a CSS mask filled with {@code color} (the app's accent color, which the server
 * resolved for the button), so a single-color glyph picks up the theme — an orange primary paints it
 * orange — and reads in both light and dark. The mask approach is reliable for an external URL (a
 * plain {@code <img>} wouldn't inherit the host's color). Renders nothing without a {@code src}.
 */
export function SsoIcon({
  src,
  color,
  size = 18,
  monochrome = false,
}: {
  src: string;
  color?: string;
  size?: number;
  monochrome?: boolean;
}) {
  if (!src) return null;
  if (!monochrome) {
    // Full color: show the logo with its own colors. object-fit keeps a non-square mark uncropped.
    return (
      <img
        src={src}
        alt=""
        aria-hidden="true"
        data-onno-sso-icon=""
        width={size}
        height={size}
        style={{ display: "inline-block", width: size, height: size, flex: "none", objectFit: "contain" }}
      />
    );
  }
  // CSS url() in a mask resolves relative to the document, so a same-origin path (e.g.
  // "/api/auth/telegram/logo.svg") works as given. Quote it to survive any odd characters.
  const maskImage = `url("${src.replace(/"/g, "%22")}")`;
  return (
    <span
      aria-hidden="true"
      data-onno-sso-icon=""
      style={{
        display: "inline-block",
        width: size,
        height: size,
        flex: "none",
        backgroundColor: color ?? "currentColor",
        // Longhands (not the `mask` shorthand) so each property is explicit and reliable; -webkit-
        // mirrors them for Safari/Chrome.
        WebkitMaskImage: maskImage,
        maskImage,
        WebkitMaskRepeat: "no-repeat",
        maskRepeat: "no-repeat",
        WebkitMaskPosition: "center",
        maskPosition: "center",
        WebkitMaskSize: "contain",
        maskSize: "contain",
      }}
    />
  );
}
