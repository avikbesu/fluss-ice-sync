import { ApiError, NetworkError } from "./nlTypes";

/**
 * The one place every backend call goes through -- turns a non-2xx
 * response into a typed [ApiError] (surfaced inline in the UI, never a
 * silent failure -- see the build prompt's edge cases) and a request that
 * never reached the server at all (offline, DNS, CORS) into a [NetworkError],
 * so callers can tell "the backend said no" from "we couldn't even ask it"
 * apart.
 */
export async function requestJson<T>(url: string, init?: RequestInit, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, { ...init, signal });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    throw new NetworkError(`Could not reach ${url}: ${(cause as Error).message ?? "unknown error"}`, cause);
  }

  const body = await response.json().catch(() => undefined);

  if (!response.ok) {
    const bodyObj = body && typeof body === "object" ? (body as Record<string, unknown>) : undefined;
    const message =
      (typeof bodyObj?.message === "string" ? bodyObj.message : undefined) ??
      `Request to ${url} failed with HTTP ${response.status}`;
    const code = typeof bodyObj?.code === "string" ? bodyObj.code : undefined;
    // trino-nl-config-service names this `fieldErrors`; a hand-rolled or
    // future trino-nl-api error body might use `details` instead (see
    // app/nl-api's own ErrorResponse) -- accept either key rather than
    // assuming one.
    const rawFieldErrors = (bodyObj?.fieldErrors ?? bodyObj?.details) as unknown;
    const fieldErrors =
      rawFieldErrors && typeof rawFieldErrors === "object" ? (rawFieldErrors as Record<string, string>) : {};
    throw new ApiError(message, response.status, code, fieldErrors);
  }

  return body as T;
}
