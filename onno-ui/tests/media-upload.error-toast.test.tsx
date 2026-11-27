import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import type { ReactElement } from "react";

// A failed upload says so once; a lapsed session (401) goes to the auth recovery instead of a
// toast. The real api.ts runs; only fetch and the toast sink are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

import { setUnauthorizedHandler } from "@/lib/api";
import { FilePicker } from "@/components/fields/file-picker";
import { ImagePicker } from "@/components/fields/image-picker";

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

const onUnauthorized = vi.fn();
beforeEach(() => setUnauthorizedHandler(onUnauthorized));
afterEach(() => {
  setUnauthorizedHandler(null);
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

/** Let any late toast land. */
async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

/** Choose `file` in the picker's file input; the upload answers `answer`, then wait for it to settle. */
async function upload(picker: ReactElement, file: File, answer: () => Response) {
  const fetchMock = vi.fn(async () => answer());
  vi.stubGlobal("fetch", fetchMock);
  const { container } = render(picker);
  fireEvent.change(container.querySelector("input[type=file]")!, { target: { files: [file] } });
  await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  await settle();
  await settle();
}

const unauthenticated = () => response(401, { error: "unauthenticated" });
const failed = () => response(500, { message: "Storage is down" });

describe.each([
  ["FilePicker", () => <FilePicker onChange={() => {}} />, new File(["x"], "report.pdf", { type: "application/pdf" })],
  ["ImagePicker", () => <ImagePicker onChange={() => {}} />, new File(["x"], "photo.png", { type: "image/png" })],
])("%s upload", (_, picker, file) => {
  it("does not toast a lapsed session (401): the auth recovery takes over", async () => {
    await upload(picker(), file, unauthenticated);

    expect(onUnauthorized).toHaveBeenCalledOnce();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("still reports any other failure once", async () => {
    await upload(picker(), file, failed);

    expect(toast.error.mock.calls).toEqual([[`Couldn't upload "${file.name}".`]]);
  });
});
