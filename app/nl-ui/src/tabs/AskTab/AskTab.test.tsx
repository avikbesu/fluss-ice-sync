import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NlApiClient } from "../../api/nlApiClient";
import { ApiError, QueryResponse } from "../../api/types";
import { renderWithProviders } from "../../test/renderWithProviders";
import { createQueryHistoryService } from "../../storage/queryHistoryService";
import { createFakeStorageService } from "../../storage/testSupport/fakeStorageService";
import { AskTab } from "./AskTab";

function successResponse(overrides: Partial<QueryResponse> = {}): QueryResponse {
  return {
    sql: "SELECT count(*) FROM iceberg.sales.orders",
    columns: [{ name: "count", type: "bigint" }],
    rows: [[42]],
    truncated: false,
    ...overrides,
  };
}

function setup() {
  const nlApiClient: NlApiClient = { query: vi.fn() };
  const historyService = createQueryHistoryService(createFakeStorageService());
  renderWithProviders(<AskTab nlApiClient={nlApiClient} historyService={historyService} />);
  return { nlApiClient };
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
    vi.mocked(nlApiClient.query).mockResolvedValueOnce(successResponse());

    await userEvent.type(screen.getByLabelText(/question or sql/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /run/i }));

    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(nlApiClient.query).toHaveBeenCalledWith(
      expect.objectContaining({ text: "how many orders?", type: "nl" }),
      expect.anything(),
    );
  });

  it("shows a loading state while the request is in flight", async () => {
    const { nlApiClient } = setup();
    let resolveQuery!: (value: QueryResponse) => void;
    vi.mocked(nlApiClient.query).mockReturnValueOnce(new Promise((resolve) => (resolveQuery = resolve)));

    await userEvent.type(screen.getByLabelText(/question or sql/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /run/i }));

    expect(await screen.findByRole("button", { name: /running/i })).toBeDisabled();

    resolveQuery(successResponse());
    await waitFor(() => expect(screen.queryByRole("button", { name: /running/i })).not.toBeInTheDocument());
  });

  it("surfaces the backend's error message inline rather than failing silently", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.query).mockRejectedValueOnce(new ApiError("mismatched input at line 1", 422, "STATEMENT_NOT_ALLOWED"));

    await userEvent.type(screen.getByLabelText(/question or sql/i), "select * from");
    await userEvent.click(screen.getByRole("button", { name: /run/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/mismatched input/i);
  });

  it("adds a successful query to the recent-history panel", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.query).mockResolvedValueOnce(successResponse());

    await userEvent.type(screen.getByLabelText(/question or sql/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /run/i }));

    await waitFor(() => expect(screen.getAllByText(/how many orders\?/i).length).toBeGreaterThan(0));
  });

  it("selecting a history entry re-renders its cached result without another API call", async () => {
    const { nlApiClient } = setup();
    vi.mocked(nlApiClient.query).mockResolvedValueOnce(successResponse());

    await userEvent.type(screen.getByLabelText(/question or sql/i), "how many orders?");
    await userEvent.click(screen.getByRole("button", { name: /run/i }));
    await screen.findByText("42");

    await userEvent.clear(screen.getByLabelText(/question or sql/i));

    const historyButtons = screen.getAllByRole("button", { name: /how many orders\?/i });
    await userEvent.click(historyButtons[0]);

    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(nlApiClient.query).toHaveBeenCalledTimes(1);
  });

  it("the Run button stays disabled for blank input", () => {
    setup();
    expect(screen.getByRole("button", { name: /run/i })).toBeDisabled();
  });
});
