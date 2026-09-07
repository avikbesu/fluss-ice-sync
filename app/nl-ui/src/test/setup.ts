import "@testing-library/jest-dom/vitest";
import "fake-indexeddb/auto";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// vite.config.ts's `test` block doesn't set `globals: true` (test files
// import describe/it/expect/etc explicitly instead) -- React Testing
// Library's automatic per-test DOM cleanup normally piggybacks on a
// global `afterEach`, which only exists when `globals: true` is set. This
// registers it explicitly instead, so component trees from one test don't
// keep accumulating in the DOM for the next one (confirmed directly: every
// query in a later test was matching stale elements from earlier tests in
// the same file before this was added).
afterEach(() => {
  cleanup();
});
