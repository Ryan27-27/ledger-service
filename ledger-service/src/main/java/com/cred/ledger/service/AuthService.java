package com.cred.ledger.service;

import com.cred.ledger.domain.Account;
import com.cred.ledger.domain.User;
import com.cred.ledger.dto.AuthResponse;
import com.cred.ledger.dto.LoginRequest;
import com.cred.ledger.dto.RegisterRequest;
import com.cred.ledger.repository.AccountRepository;
import com.cred.ledger.repository.UserRepository;
import com.cred.ledger.security.JwtService;
import com.cred.ledger.service.exception.InvalidCredentialsException;
import com.cred.ledger.service.exception.UsernameAlreadyExistsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, AccountRepository accountRepository,
                        PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Registration provisions both a User (auth identity) and an Account
     * (ledger identity) atomically -- a person can't have login credentials
     * without a points account to go with them, and vice versa.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UsernameAlreadyExistsException("Username already taken: " + request.username());
        }

        Account account = accountRepository.save(new Account(request.username()));

        User user = new User(request.username(), passwordEncoder.encode(request.password()), account.getId());
        userRepository.save(user);

        String token = jwtService.issueToken(user.getUsername(), user.getAccountId(), user.getRole());
        return AuthResponse.of(token, jwtService.getExpirySeconds(), user.getAccountId(), user.getUsername());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String token = jwtService.issueToken(user.getUsername(), user.getAccountId(), user.getRole());
        return AuthResponse.of(token, jwtService.getExpirySeconds(), user.getAccountId(), user.getUsername());
    }
}
