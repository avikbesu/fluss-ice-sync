import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { NlConfigClient } from "../../api/nlConfigClient";
import { ConfigSummary, NetworkError, TableConfig } from "../../api/nlTypes";
import { renderWithProviders } from "../../test/renderWithProviders";
import { ConfigTab } from "./ConfigTab";

function summary(overrides: Partial<ConfigSummary> = {}): ConfigSummary {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "orders_config",
    source: "orders.csv",
    destination: { catalog: "iceberg", schema: "sales", table: "orders" },
    ...overrides,
  };
}

function fullConfig(overrides: Partial<TableConfig> = {}): TableConfig {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "orders_config",
    source: "orders.csv",
    destination: { catalog: "iceberg", schema: "sales", table: "orders" },
    columns: [{ name: "id", inferredType: "integer", description: "primary key" }],
    businessDescription: "Orders placed by partners.",
    exampleQuestions: ["How many orders last week?"],
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function mockClient(overrides: Partial<NlConfigClient> = {}): NlConfigClient {
  return {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn(),
    upsert: vi.fn(),
    ...overrides,
  };
}

describe("ConfigTab", () => {
  it("shows a loading state while the config list is being fetched", () => {
    const client = mockClient({ list: vi.fn().mockReturnValue(new Promise(() => {})) });
    renderWithProviders(<ConfigTab nlConfigClient={client} />);
    expect(screen.getByText(/loading configs/i)).toBeInTheDocument();
  });

  it("shows an empty-carousel state when there are no configs yet", async () => {
    const client = mockClient({ list: vi.fn().mockResolvedValue([]) });
    renderWithProviders(<ConfigTab nlConfigClient={client} />);
    expect(await screen.findByText(/no configs yet/i)).toBeInTheDocument();
  });

  it("shows a clear error state when the config service is unreachable, not a silent failure", async () => {
    const client = mockClient({ list: vi.fn().mockRejectedValue(new NetworkError("Could not reach config service")) });
    renderWithProviders(<ConfigTab nlConfigClient={client} />);
    expect(await screen.findByText(/could not reach the config service/i)).toBeInTheDocument();
  });

  it("lists configs and shows a selected one's detail", async () => {
    const client = mockClient({
      list: vi.fn().mockResolvedValue([summary()]),
      get: vi.fn().mockResolvedValue(fullConfig()),
    });
    renderWithProviders(<ConfigTab nlConfigClient={client} />);

    const card = await screen.findByRole("button", { name: /orders_config/i });
    await userEvent.click(card);

    expect(await screen.findByText(/orders placed by partners/i)).toBeInTheDocument();
    expect(client.get).toHaveBeenCalledWith(summary().id);
  });

  it("shows a clear detail error without pretending the config loaded", async () => {
    const client = mockClient({
      list: vi.fn().mockResolvedValue([summary()]),
      get: vi.fn().mockRejectedValue(new Error("HTTP 404")),
    });
    renderWithProviders(<ConfigTab nlConfigClient={client} />);

    await userEvent.click(await screen.findByRole("button", { name: /orders_config/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/404/);
  });

  it("starting a new config shows the upload step", async () => {
    const client = mockClient();
    renderWithProviders(<ConfigTab nlConfigClient={client} />);

    await userEvent.click(await screen.findByRole("button", { name: /new config/i }));

    expect(screen.getByText(/1\. upload a sample file/i)).toBeInTheDocument();
  });
});
