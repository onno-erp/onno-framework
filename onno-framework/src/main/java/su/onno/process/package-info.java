/**
 * Typed business-process definition language and durable process runtime contracts.
 *
 * <p>The public API models route structure with Java enum step keys; typed human, automatic,
 * decision, timer, structured parallel fork/join, subprocess, and terminal handles; explicit
 * version migrations; and enum outcomes. Core supplies token-based durable process/work-item
 * persistence, cancellation, timers, and candidate/assignee routing. The Spring starter supplies
 * JSON and JobRunr wiring; the UI starter supplies inspectable process APIs and the authenticated
 * inbox.</p>
 */
package su.onno.process;
