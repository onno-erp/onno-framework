import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DatePicker } from "../src/components/date-picker";
afterEach(cleanup);
it("uses the ONNO calendar and time segments, and disables them while saving",()=>{
 const props={value:"2026-09-12T14:30",onChange:vi.fn(),includeTime:true,"aria-label":"Call date and time"};
 const {rerender}=render(<DatePicker {...props} />);
 expect(screen.getByRole("button",{name:/calendar/i})).toBeEnabled();
 expect(screen.getAllByRole("spinbutton").length).toBeGreaterThanOrEqual(5);
 expect(document.querySelector('input[type="datetime-local"]')).toHaveAttribute("tabindex", "-1");
 rerender(<DatePicker {...props} isDisabled />);
 expect(screen.getByRole("button",{name:/calendar/i})).toBeDisabled();
});
