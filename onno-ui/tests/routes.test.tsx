import { describe, expect, it } from "vitest";
import { matchRoute, route } from "@/lib/routes";

const A = () => null;
const B = () => null;
const C = () => null;

describe("matchRoute", () => {
  it("matches an exact route, ignoring the query string and slashes", () => {
    const routes = [route("/exact/orders/", A)];
    expect(matchRoute(routes, "/exact/orders?status=open")).toMatchObject({ Component: A, params: {} });
    expect(matchRoute(routes, "/exact/orders/o1")).toBeNull();
    expect(matchRoute(routes, "/exact")).toBeNull();
  });

  it("captures and decodes :params and the * tail", () => {
    const routes = [route("/params/:kind/:id", A), route("/tail/*", B)];
    expect(matchRoute(routes, "/params/orders/a%20b")?.params).toEqual({ kind: "orders", id: "a b" });
    expect(matchRoute(routes, "/tail/x/y/z")).toMatchObject({ Component: B, params: { "*": "x/y/z" } });
    expect(matchRoute(routes, "/tail")?.params).toEqual({ "*": "" });
  });

  it("prefers literal segments over params, and params over wildcards", () => {
    const routes = [route("/spec/*", C), route("/spec/orders/:id", B), route("/spec/orders/new", A)];
    expect(matchRoute(routes, "/spec/orders/new")?.Component).toBe(A);
    expect(matchRoute(routes, "/spec/orders/o1")?.Component).toBe(B);
    expect(matchRoute(routes, "/spec/other")?.Component).toBe(C);
  });

  it("lets the later of two identical patterns win", () => {
    const routes = [route("/replace", A, { clientOnly: true }), route("/replace/", C)];
    expect(matchRoute(routes, "/replace")).toMatchObject({ Component: C, options: {} });
  });
});
