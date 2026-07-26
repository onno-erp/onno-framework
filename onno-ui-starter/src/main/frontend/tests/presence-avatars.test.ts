import { describe, expect, it } from "vitest";
import { glassAvatar } from "@/components/presence-avatars";

describe("glassAvatar", () => {
  it("builds a deterministic DiceBear 10 Glass URL with an encoded seed", () => {
    expect(glassAvatar("Felix & Mara")).toBe(
      "https://api.dicebear.com/10.x/glass/svg?seed=Felix%20%26%20Mara",
    );
  });

  it("uses a stable fallback seed", () => {
    expect(glassAvatar(null)).toBe(
      "https://api.dicebear.com/10.x/glass/svg?seed=unknown",
    );
  });
});
