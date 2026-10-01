package com.cred.ledger.service;

import com.cred.ledger.dto.AccountSummary;
import com.cred.ledger.dto.PageResponse;
import com.cred.ledger.repository.LedgerQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private final LedgerQueryRepository queryRepository;

    public AdminService(LedgerQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountSummary> listAccounts(String query, int page, int size) {
        long total = queryRepository.countAccounts(query);
        return PageResponse.of(queryRepository.findAccounts(query, size, (long) page * size), page, size, total);
    }
}
