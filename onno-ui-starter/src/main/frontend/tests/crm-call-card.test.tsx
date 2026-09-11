import * as React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
vi.mock("@onno/widget-sdk",async()=>({...React,...await import("../src/components/ui/button"),CommentBody:({body}:{body:string})=><span>{body}</span>}));
import { callActivity, CallActivityCard } from "../../../../../onno-crm-starter/src/main/widgets/CallActivityCard";
afterEach(cleanup);
it("renders legacy call summaries and extracts only safe recording links",()=>{
 const activity=callActivity("Call completed\nDiscussed the wedding budget.\nRecording: https://example.com/call")!;
 render(<CallActivityCard activity={activity} author="Alice" at="10:30" />);
 expect(screen.getByRole("article",{name:"Phone call"})).toBeVisible();
 expect(screen.getByText("Discussed the wedding budget.")).toBeVisible();
 expect(screen.getByRole("link",{name:"Open recording"})).toHaveAttribute("href","https://example.com/call");
 expect(callActivity("Call completed\nNotes\nRecording: javascript:alert(1)")?.recording).toBeUndefined();
 expect(callActivity("Conversation assigned\nAlice")).toBeNull();
});
it("expands a long summary and retains scheduled time",()=>{
 const activity=callActivity(`Call planned · Scheduled for 12 Sep 2026 15:00\n${"Notes ".repeat(80)}Final decision.`)!;
 render(<CallActivityCard activity={activity} author="Alice" />);
 expect(screen.getByText("12 Sep 2026 15:00")).toBeVisible();
 expect(screen.queryByText(/Final decision/)).toBeNull();
 fireEvent.click(screen.getByRole("button",{name:"Read full summary"}));
 expect(screen.getByText(/Final decision/)).toBeVisible();
});
