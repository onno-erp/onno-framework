import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DatePicker } from "../src/components/date-picker";

afterEach(cleanup);

/**
 * An empty picker opens on the current month, not on a date compiled into the bundle. The month the
 * calendar lands on comes from react-aria's `value ?? placeholderValue`, so this is the assertion
 * that keeps a literal from creeping back into the placeholder.
 */
function currentMonthHeading() {
  const now = new Date();
  return new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" }).format(now);
}

it("opens an empty date field on today's month", async () => {
  render(<DatePicker value="" onChange={vi.fn()} aria-label="Wedding date" />);
  fireEvent.click(screen.getByRole("button", { name: /calendar/i }));
  await waitFor(() => expect(screen.getByText(currentMonthHeading())).toBeInTheDocument());
  // Today is on the grid the picker opened on, so it is one click away rather than months of paging.
  expect(document.querySelector("[data-today]")).not.toBeNull();
});

it("still opens a filled date field on the month it holds", async () => {
  render(<DatePicker value="2031-04-17" onChange={vi.fn()} aria-label="Wedding date" />);
  fireEvent.click(screen.getByRole("button", { name: /calendar/i }));
  const april2031 = new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" })
    .format(new Date(2031, 3, 17));
  await waitFor(() => expect(screen.getByText(april2031)).toBeInTheDocument());
});
