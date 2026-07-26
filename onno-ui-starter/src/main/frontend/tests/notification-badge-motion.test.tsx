import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { NotificationBadgeMotion } from "@/components/ui/notification-badge-motion";

describe("NotificationBadgeMotion", () => {
  it("keeps the badge mounted and toggles its transition state with unread count", () => {
    const { container, rerender } = render(
      <NotificationBadgeMotion count={0}><span>0</span></NotificationBadgeMotion>,
    );
    const badge = container.querySelector(".t-badge");
    expect(badge).toHaveAttribute("data-open", "false");

    rerender(<NotificationBadgeMotion count={1}><span>1</span></NotificationBadgeMotion>);
    expect(badge).toHaveAttribute("data-open", "true");

    rerender(<NotificationBadgeMotion count={0}><span>0</span></NotificationBadgeMotion>);
    expect(badge).toHaveAttribute("data-open", "false");
  });
});
