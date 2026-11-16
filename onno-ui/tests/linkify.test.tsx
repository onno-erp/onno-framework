import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { linkify } from "@/lib/linkify";

afterEach(cleanup);

describe("linkify", () => {
  it("leaves plain text untouched", () => {
    const { container } = render(<p>{linkify("no links here")}</p>);
    expect(container.textContent).toBe("no links here");
    expect(container.querySelector("a")).toBeNull();
  });

  it("turns a bare http(s) URL into a new-tab link with safe rel", () => {
    render(<p>{linkify("see https://example.com/docs for more")}</p>);
    const a = screen.getByRole("link", { name: "https://example.com/docs" });
    expect(a).toHaveAttribute("href", "https://example.com/docs");
    expect(a).toHaveAttribute("target", "_blank");
    expect(a).toHaveAttribute("rel", "noreferrer noopener");
  });

  it("links multiple URLs in one body", () => {
    render(<p>{linkify("a http://a.test and b https://b.test")}</p>);
    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  it("drops trailing sentence punctuation from the href", () => {
    render(<p>{linkify("read https://example.com.")}</p>);
    const a = screen.getByRole("link");
    expect(a).toHaveAttribute("href", "https://example.com");
    expect(a.parentElement?.textContent).toBe("read https://example.com.");
  });

  it("keeps a closing paren that belongs to the URL", () => {
    render(<p>{linkify("https://en.wikipedia.org/wiki/Foo_(bar)")}</p>);
    expect(screen.getByRole("link")).toHaveAttribute(
      "href",
      "https://en.wikipedia.org/wiki/Foo_(bar)",
    );
  });

  it("does not linkify non-http schemes", () => {
    render(<p>{linkify("javascript:alert(1) and mailto:a@b.test")}</p>);
    expect(screen.queryByRole("link")).toBeNull();
  });
});
