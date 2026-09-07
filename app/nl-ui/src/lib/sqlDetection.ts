export type DetectedInputType = "sql" | "nl";

/**
 * Client-side SQL-vs-NL routing hint for the Ask tab's single input box --
 * **a UX convenience only, never a trust boundary**. The result is sent to
 * `trino-nl-api` as an explicit `type` field on the request, but the
 * backend is required to independently validate/parse the input
 * regardless of what this says; a mislabeled request must never let
 * anything skip that service's own read-only validation. See
 * src/api/nlApiClient.ts and the README.
 *
 * The rule is deliberately simple: the trimmed, lowercased input is SQL if
 * it starts with one of [keywords] (a whole-word match -- "select" matches
 * "SELECT * FROM t" but not "selection criteria"), otherwise NL.
 *
 * **Known, deliberate limitation**: with the default keyword list
 * (`["select"]`, see src/config/settings.ts), a CTE-style query starting
 * with `WITH ... SELECT`, or a bare `SHOW`/`EXPLAIN`/`DESCRIBE` statement,
 * is misclassified as NL -- it gets sent to the backend's NL path, which
 * (per the read-only-everywhere contract) still has to independently
 * decide what to do with text that isn't a real question, rather than
 * this function silently mis-routing something dangerous. Documented here
 * rather than "fixed" by guessing at more keywords, since the right fix is
 * deployment-specific: add `"with"`, `"show"`, `"explain"`, `"describe"`
 * to `sqlDetectionKeywords` if a deployment's users type those often
 * enough for it to matter -- this function takes the keyword list as a
 * parameter for exactly that reason, rather than hardcoding one.
 */
export function detectInputType(rawInput: string, keywords: string[]): DetectedInputType {
  const normalized = rawInput.trim().toLowerCase();
  if (normalized.length === 0) return "nl";

  const isSql = keywords.some((keyword) => startsWithWholeWord(normalized, keyword.toLowerCase()));
  return isSql ? "sql" : "nl";
}

function startsWithWholeWord(normalized: string, keyword: string): boolean {
  if (keyword.length === 0 || !normalized.startsWith(keyword)) return false;
  const nextChar = normalized.charAt(keyword.length);
  return nextChar === "" || !/[a-z0-9_]/.test(nextChar);
}
