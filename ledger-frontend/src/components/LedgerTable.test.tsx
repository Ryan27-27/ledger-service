import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { LedgerEntryResponse } from "../lib/types";
import { LedgerTable } from "./LedgerTable";

const entry = (over: Partial<LedgerEntryResponse>): LedgerEntryResponse => ({
  id: "e1",
  accountId: "acc",
  amount: 50,
  type: "CREDIT",
  status: "POSTED",
  referenceId: "bonus",
  remarks: null,
  reversalOf: null,
  createdAt: "2026-01-01T10:00:00Z",
  runningBalance: 50,
  ...over,
});

const baseProps = {
  canReverse: true,
  hasMore: false,
  loadingMore: false,
  onLoadMore: () => {},
  onReverse: vi.fn().mockResolvedValue(undefined),
};

describe("LedgerTable", () => {
  it("shows the server-computed running balance for each row", () => {
    render(
      <LedgerTable
        {...baseProps}
        entries={[
          entry({ id: "e2", type: "DEBIT", amount: 30, referenceId: "voucher", runningBalance: 20 }),
          entry({ id: "e1", runningBalance: 50 }),
        ]}
      />,
    );

    expect(screen.getAllByTestId("running-balance").map((n) => n.textContent)).toEqual(["20.00", "50.00"]);
  });

  it("offers reversal only on live, original entries", () => {
    render(
      <LedgerTable
        {...baseProps}
        entries={[
          entry({ id: "c", referenceId: "reversal-of-x", reversalOf: "x", type: "DEBIT" }),
          entry({ id: "r", referenceId: "already-reversed", status: "REVERSED" }),
          entry({ id: "ok", referenceId: "reversible" }),
        ]}
      />,
    );

    expect(screen.getAllByRole("button", { name: /^reverse /i })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Reverse reversible" })).toBeInTheDocument();
    expect(screen.getByText("reversal")).toBeInTheDocument();
    expect(screen.getByText(/↺ reversed/)).toBeInTheDocument();
  });

  it("hides reverse buttons entirely when the user may not reverse", () => {
    render(<LedgerTable {...baseProps} canReverse={false} entries={[entry({})]} />);

    expect(screen.queryByRole("button", { name: /^reverse /i })).not.toBeInTheDocument();
  });

  it("reverses through a modal and passes a reason plus an idempotency key", async () => {
    const onReverse = vi.fn().mockResolvedValue(undefined);
    render(<LedgerTable {...baseProps} onReverse={onReverse} entries={[entry({ id: "e9", referenceId: "oops" })]} />);

    await userEvent.click(screen.getByRole("button", { name: "Reverse oops" }));
    const dialog = screen.getByRole("dialog", { name: "Reverse entry" });
    await userEvent.type(within(dialog).getByPlaceholderText("granted in error"), "duplicate grant");
    await userEvent.click(within(dialog).getByRole("button", { name: "Reverse" }));

    expect(onReverse).toHaveBeenCalledWith("e9", "duplicate grant", expect.stringMatching(/^reverse-/));
  });

  it("pages in older entries on demand", async () => {
    const onLoadMore = vi.fn();
    render(<LedgerTable {...baseProps} hasMore onLoadMore={onLoadMore} entries={[entry({})]} />);

    await userEvent.click(screen.getByRole("button", { name: /load older entries/i }));

    expect(onLoadMore).toHaveBeenCalledOnce();
  });

  it("renders an empty state", () => {
    render(<LedgerTable {...baseProps} entries={[]} />);
    expect(screen.getByText("No entries yet.")).toBeInTheDocument();
  });
});
