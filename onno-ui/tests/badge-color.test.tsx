import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { Badge } from "../src/components/ui/badge";
afterEach(cleanup);
it("uses the configured fill and contrasting text for status and tag colors", () => {
 render(<><Badge color="#8478ff">Qualified</Badge><Badge color="#ffeeaa">Light</Badge><Badge color="invalid" variant="secondary">Default</Badge></>);
 expect(screen.getByText("Qualified").style.backgroundColor).toBe("rgb(132, 120, 255)");
 expect(screen.getByText("Qualified").style.color).toBe("rgb(255, 255, 255)");
 expect(screen.getByText("Light").style.color).toBe("rgb(31, 41, 55)");
 expect(screen.getByText("Default").style.backgroundColor).toBe("");
});
