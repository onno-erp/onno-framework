import { render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppToaster } from "@/components/ui/toaster";

const { toasterSpy, useThemeMock } = vi.hoisted(() => ({
  toasterSpy: vi.fn(),
  useThemeMock: vi.fn(),
}));

vi.mock("sonner", () => ({
  Toaster: (props: Record<string, unknown>) => {
    toasterSpy(props);
    return <div data-testid="toaster" data-theme={String(props.theme)} />;
  },
}));

vi.mock("@/providers/theme-provider", () => ({
  useTheme: useThemeMock,
}));

describe("AppToaster", () => {
  beforeEach(() => {
    toasterSpy.mockReset();
    useThemeMock.mockReturnValue({ theme: "dark" });
  });

  it("owns the monochrome animated Sonner stack configuration", () => {
    render(<AppToaster />);

    expect(toasterSpy).toHaveBeenCalledTimes(1);
    const props = toasterSpy.mock.calls[0][0];
    expect(props).toMatchObject({
      theme: "dark",
      position: "bottom-right",
      expand: false,
      richColors: false,
      visibleToasts: 4,
      gap: 10,
      className: "onno-toaster",
    });
    expect(props.toastOptions.className).toContain("t-toast");
    expect(props.toastOptions.className).toContain("onno-toast");
    expect(props.swipeDirections).toEqual(["right", "bottom"]);
  });
});
