import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getRefreshToken,
  getToken,
  ledgerApi,
  LedgerApiError,
  newIdempotencyKey,
  setSessionExpiredHandler,
  setTokens,
} from "./api";

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("api client", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    setSessionExpiredHandler(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends the bearer token and the caller's idempotency key unchanged", async () => {
    setTokens("access-1", "refresh-1");
    fetchMock.mockResolvedValueOnce(jsonResponse(201, { id: "e1" }));

    await ledgerApi.credit("acc-1", 25, "bonus", "key-123");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/accounts/acc-1/credits");
    expect(init.headers.Authorization).toBe("Bearer access-1");
    expect(JSON.parse(init.body)).toEqual({ amount: 25, referenceId: "bonus", idempotencyKey: "key-123" });
  });

  it("turns error responses into LedgerApiError with the server's message and code", async () => {
    setTokens("a", "r");
    fetchMock.mockResolvedValueOnce(
      jsonResponse(422, { status: 422, code: "INSUFFICIENT_BALANCE", message: "Insufficient balance" }),
    );

    await expect(ledgerApi.debit("acc", 5, "x", "k")).rejects.toMatchObject({
      name: "LedgerApiError",
      status: 422,
      code: "INSUFFICIENT_BALANCE",
      message: "Insufficient balance",
    });
  });

  it("refreshes once on 401, stores the rotated tokens, and retries the request", async () => {
    setTokens("expired", "refresh-1");
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { message: "expired" })) // original call
      .mockResolvedValueOnce(jsonResponse(200, { token: "fresh", refreshToken: "refresh-2" })) // refresh
      .mockResolvedValueOnce(jsonResponse(200, { accountId: "acc", balance: 10 })); // retry

    const result = await ledgerApi.getBalance("acc");

    expect(result.balance).toBe(10);
    expect(getToken()).toBe("fresh");
    expect(getRefreshToken()).toBe("refresh-2");
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe("Bearer fresh");
  });

  it("shares ONE refresh call between concurrent 401s (refresh tokens are single-use)", async () => {
    setTokens("expired", "refresh-1");
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string, init: RequestInit & { headers: Record<string, string> }) => {
      if (url.endsWith("/auth/refresh")) {
        refreshCalls++;
        return jsonResponse(200, { token: "fresh", refreshToken: "refresh-2" });
      }
      return init.headers.Authorization === "Bearer fresh"
        ? jsonResponse(200, { accountId: "acc", balance: 1 })
        : jsonResponse(401, { message: "expired" });
    });

    await Promise.all([ledgerApi.getBalance("acc"), ledgerApi.getAudit("acc"), ledgerApi.getHistory("acc")]);

    expect(refreshCalls).toBe(1);
  });

  it("clears tokens and signals session expiry when the refresh is rejected", async () => {
    setTokens("expired", "revoked");
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { message: "expired" }))
      .mockResolvedValueOnce(jsonResponse(401, { message: "Refresh token has already been used" }));

    await expect(ledgerApi.getBalance("acc")).rejects.toBeInstanceOf(LedgerApiError);

    expect(expired).toHaveBeenCalledOnce();
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it("does not try to refresh when login itself returns 401", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { code: "INVALID_CREDENTIALS", message: "bad" }));

    await expect(ledgerApi.login("a", "b")).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("generates distinct idempotency keys", () => {
    expect(newIdempotencyKey("credit")).not.toBe(newIdempotencyKey("credit"));
    expect(newIdempotencyKey("credit")).toMatch(/^credit-/);
  });
});
