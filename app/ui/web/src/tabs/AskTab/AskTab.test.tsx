import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NlApiClient } from "../../api/nlApiClient";
import { NlConfigClient } from "../../api/nlConfigClient";
import { ApiError, AskResponse } from "../../api/nlTypes";
import { renderWithProviders } from "../../test/renderWithProviders";
import { createQueryHistoryService } from "../../storage/queryHistoryService";
import { createFakeStorageService } from "../../storage/testSupport/fakeStorageService";
import { AskTab } from "./AskTab";

function successResponse(overrides: Partial<AskResponse> = {}): AskResponse {
  return {
    sql: "SELECT count(*) FROM iceberg.sales.orders",
    needsClarification: false,
    clarificationQuestion: null,
    tablesUsed: ["iceberg.sales.orders"],
    columns: [{ name: "count", type: "bigint" }],
    rows: [[42]],
    rowCount: 1,
    truncated: false,
    durationMs: 12,
    ...overrides,
  };
}

function setup() {
  const nlApiClient: NlApiClient = { ask: vi.fn() };
  const nlConfigClient: NlConfigClient = { list: vi.fn(), get: vi.fn(), upsert: vi.fn() };
  const historyService = createQueryHistoryService(createFakeStorageService());
  renderWithProviders(<AskTab nlApiClient={nlApiClient} nlConfigClient={nlConfigClient} historyService={historyService} />);
  return { nlApiClient, nlConfigClient };
}

describe("AskTab", () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  it("shows an empty history state before any query has run", () => {
    setup();
    expect(screen.getByText(/no queries yet/i)).toBeInTheDocument();
  });

  it("runs a query and renders the result", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.ask).mockResolvedValueOnce(successResponse());

    await userEvent.type(screen.getByLabelText(/question/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /ask/i }));

    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(nlApiClient.ask).toHaveBeenCalledWith(
      expect.objectContaining({ question: "how many orders?" }),
      expect.anything(),
    );
  });

  it("shows a loading state while the request is in flight", async () => {
    const { nlApiClient } = setup();
    let resolveAsk!: (value: AskResponse) => void;
    vi.mocked(nlApiClient.ask).mockReturnValueOnce(new Promise((resolve) => (resolveAsk = resolve)));

    await userEvent.type(screen.getByLabelText(/question/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /ask/i }));

    expect(await screen.findByRole("button", { name: /asking/i })).toBeDisabled();

    resolveAsk(successResponse());
    await waitFor(() => expect(screen.queryByRole("button", { name: /asking/i })).not.toBeInTheDocument());
  });

  it("surfaces the backend's error message inline rather than failing silently", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.ask).mockRejectedValueOnce(new ApiError("mismatched input at line 1", 422, "STATEMENT_NOT_ALLOWED"));

    await userEvent.type(screen.getByLabelText(/question/i), "how many orders");
    await userEvent.click(screen.getByRole("button", { name: /ask/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/mismatched input/i);
  });

  it("shows the clarification question instead of a result when nl-api can't generate a confident query", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.ask).mockResolvedValueOnce(
      successResponse({ sql: null, needsClarification: true, clarificationQuestion: "Which table do you mean?" }),
    );

    await userEvent.type(screen.getByLabelText(/question/i), "how many?");
    await userEvent.click(screen.getByRole("button", { name: /ask/i }));

    expect(await screen.findByRole("status")).toHaveTextContent(/which table do you mean/i);
  });

  it("adds a successful query to the recent-history panel", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.ask).mockResolvedValueOnce(successResponse());

    await userEvent.type(screen.getByLabelText(/question/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /ask/i }));

    await waitFor(() => expect(screen.getAllByText(/how many orders\?/i).length).toBeGreaterThan(0));
  });

  it("selecting a history entry re-renders its cached result without another API call", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.ask).mockResolvedValueOnce(successResponse());

    await userEvent.type(screen.getByLabelText(/question/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /ask/i }));
    await screen.findByText("42");

    await userEvent.clear(screen.getByLabelText(/question/i));

    const historyButtons = screen.getAllByRole("button", { name: /how many orders\?/i });
    await userEvent.click(historyButtons[0]);

    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(nlApiClient.ask).toHaveBeenCalledTimes(1);
  });

  it("the Ask button stays disabled for blank input", () => {
    setup();
    expect(screen.getByRole("button", { name: /ask/i })).toBeDisabled();
  });
});
