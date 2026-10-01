import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TransactionModal } from "./TransactionModal";

describe("TransactionModal", () => {
  it.each([["0"], ["-5"], ["abc"], ["1.234"], [""]])("rejects the amount %j without calling the API", async (bad) => {
    const onSubmit = vi.fn();
    render(<TransactionModal type="CREDIT" onClose={() => {}} onSubmit={onSubmit} />);

    if (bad) await userEvent.type(screen.getByPlaceholderText("0.00"), bad);
    await userEvent.type(screen.getByPlaceholderText("bill-payment-9821"), "ref-1");
    await userEvent.click(screen.getByRole("button", { name: /credit/i }));

    expect(screen.getByText(/greater than 0/i)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits a valid amount and closes", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    render(<TransactionModal type="DEBIT" onClose={onClose} onSubmit={onSubmit} />);

    await userEvent.type(screen.getByPlaceholderText("0.00"), "12.50");
    await userEvent.type(screen.getByPlaceholderText("voucher-redemption-4471"), "voucher-1");
    await userEvent.click(screen.getByRole("button", { name: /redeem/i }));

    expect(onSubmit).toHaveBeenCalledWith(12.5, "voucher-1", expect.stringMatching(/^debit-/));
    expect(onClose).toHaveBeenCalled();
  });

  it("re-sends the SAME idempotency key when the user retries after a failure", async () => {
    const onSubmit = vi
      .fn()
      .mockRejectedValueOnce(new Error("Request timed out"))
      .mockResolvedValueOnce(undefined);
    render(<TransactionModal type="DEBIT" onClose={() => {}} onSubmit={onSubmit} />);

    await userEvent.type(screen.getByPlaceholderText("0.00"), "5");
    await userEvent.type(screen.getByPlaceholderText("voucher-redemption-4471"), "voucher-1");
    await userEvent.click(screen.getByRole("button", { name: /redeem/i }));
    expect(await screen.findByText("Request timed out")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /redeem/i }));

    expect(onSubmit).toHaveBeenCalledTimes(2);
    expect(onSubmit.mock.calls[0][2]).toBe(onSubmit.mock.calls[1][2]);
  });
});
