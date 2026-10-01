package com.cred.ledger.repository;

import com.cred.ledger.domain.EntryStatus;
import com.cred.ledger.domain.EntryType;
import com.cred.ledger.dto.AccountSummary;
import com.cred.ledger.dto.LedgerEntryResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-side queries that are awkward in JPQL: a server-computed running
 * balance (window function) and the admin account listing. Plain SQL keeps the
 * query explicit and avoids ORM type-mapping surprises on projections.
 */
@Repository
public class LedgerQueryRepository {

    private static final RowMapper<LedgerEntryResponse> ENTRY_MAPPER = (rs, i) -> new LedgerEntryResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getBigDecimal("amount"),
            EntryType.valueOf(rs.getString("type")),
            EntryStatus.valueOf(rs.getString("status")),
            rs.getString("reference_id"),
            rs.getString("remarks"),
            rs.getObject("reversal_of", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getBigDecimal("running_balance")
    );

    private static final RowMapper<AccountSummary> ACCOUNT_MAPPER = (rs, i) -> new AccountSummary(
            rs.getObject("account_id", UUID.class),
            rs.getString("username"),
            rs.getString("role"),
            rs.getBigDecimal("cached_balance"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest-first page of entries. The running balance is computed over the
     * whole account history in chronological order (before paging), so a row's
     * balance is correct no matter which page it lands on.
     */
    public List<LedgerEntryResponse> findPageWithRunningBalance(UUID accountId, int limit, long offset) {
        String sql = """
            SELECT id, account_id, amount, type, status, reference_id, remarks,
                   reversal_of, created_at, running_balance
            FROM (
                SELECT e.*,
                       SUM(CASE WHEN e.type = 'CREDIT' THEN e.amount ELSE -e.amount END)
                           OVER (ORDER BY e.created_at, e.id) AS running_balance
                FROM ledger_entries e
                WHERE e.account_id = :accountId
            ) t
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("limit", limit)
                .addValue("offset", offset), ENTRY_MAPPER);
    }

    public List<AccountSummary> findAccounts(String query, int limit, long offset) {
        String sql = """
            SELECT a.id AS account_id, u.username, u.role, a.cached_balance, a.created_at
            FROM accounts a
            JOIN app_users u ON u.account_id = a.id
            WHERE u.username LIKE :pattern
            ORDER BY u.username
            LIMIT :limit OFFSET :offset
            """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("pattern", likePattern(query))
                .addValue("limit", limit)
                .addValue("offset", offset), ACCOUNT_MAPPER);
    }

    public long countAccounts(String query) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM accounts a JOIN app_users u ON u.account_id = a.id WHERE u.username LIKE :pattern",
                new MapSqlParameterSource("pattern", likePattern(query)), Long.class);
        return n == null ? 0 : n;
    }

    /** Usernames are stored lower-case, so a lower-cased LIKE is enough. LIKE wildcards in the input are escaped. */
    private static String likePattern(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        q = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + q + "%";
    }
}
