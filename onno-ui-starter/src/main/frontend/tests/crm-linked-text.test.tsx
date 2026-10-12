import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { LinkedText } from "../../../../../onno-crm-starter/src/main/widgets/LinkedText";
afterEach(cleanup);
it("links bare http(s) URLs in message text and keeps trailing punctuation as text", () => {
  const { container } = render(<p>{<LinkedText text={"Gallery: https://example.com/a?b=1. Also (http://x.io/y) javascript:alert(1)"} />}</p>);
  const links = screen.getAllByRole("link");
  expect(links.map(l => l.getAttribute("href"))).toEqual(["https://example.com/a?b=1", "http://x.io/y"]);
  expect(links[0]).toHaveAttribute("target", "_blank");
  expect(links[0]).toHaveAttribute("rel", "noopener noreferrer");
  expect(container.textContent).toBe("Gallery: https://example.com/a?b=1. Also (http://x.io/y) javascript:alert(1)");
});
it("leaves text without URLs untouched", () => {
  render(<p data-testid="body"><LinkedText text="No links here" /></p>);
  expect(screen.getByTestId("body").textContent).toBe("No links here");
  expect(screen.queryByRole("link")).toBeNull();
});
