import "@testing-library/jest-dom/vitest";
import { afterAll } from "vitest";

// Radix FocusScope restores focus in a setTimeout(0) after a dialog unmounts. A dialog still open
// when a file's last test ends unmounts in cleanup, and that timer can then fire after Vitest has
// torn jsdom down: `new CustomEvent` resolves to Node's, jsdom's dispatchEvent rejects it, and the
// run fails with an unhandled error blamed on whichever file was running. Wait one macrotask so
// those timers run while jsdom is still installed. Setup-file hooks run after the file's own.
afterAll(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));
