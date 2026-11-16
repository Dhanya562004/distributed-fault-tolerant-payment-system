package com.paymentsystem.controller;

import com.paymentsystem.dto.request.CreateUserRequest;
import com.paymentsystem.dto.response.PagedResponse;
import com.paymentsystem.dto.response.UserResponse;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User Account Management", description = "Endpoints for user accounts and wallet balance tracking")
public class UserController {

    private final UserRepository userRepository;
    private final com.paymentsystem.service.LedgerService ledgerService;

    @org.springframework.beans.factory.annotation.Value("${app.ledger.enabled:true}")
    private boolean ledgerEnabled = true;

    @Autowired
    public UserController(UserRepository userRepository,
                          @Autowired(required = false) com.paymentsystem.service.LedgerService ledgerService) {
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
    }

    public UserController(UserRepository userRepository) {
        this(userRepository, null);
    }

    public void setLedgerEnabled(boolean ledgerEnabled) {
        this.ledgerEnabled = ledgerEnabled;
    }

    @PostMapping
    @Operation(summary = "Create User Account", description = "Provisions a new user account with initial balance")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = new User(request.getUserCode(), request.getName(), request.getEmail(), request.getInitialBalance());
        User saved = userRepository.save(user);
        if (saved.getBalance() != null && saved.getBalance() > 0 && ledgerService != null && ledgerEnabled) {
            ledgerService.recordOpeningBalance(saved.getUserCode(), saved.getBalance(), "USD");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @GetMapping
    @Operation(summary = "List All Users", description = "Retrieves all registered user accounts with pagination support")
    public ResponseEntity<PagedResponse<UserResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<UserResponse> users = userRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(PagedResponse.paginate(users, page, size));
    }

    @GetMapping("/{userCode}")
    @Operation(summary = "Get User Details", description = "Retrieves user account info by userCode")
    public ResponseEntity<UserResponse> getUser(@PathVariable String userCode) {
        User user = userRepository.findByUserCode(userCode)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with userCode: " + userCode));
        return ResponseEntity.ok(mapToResponse(user));
    }

    private UserResponse mapToResponse(User user) {
        UserResponse res = new UserResponse();
        res.setId(user.getId());
        res.setUserCode(user.getUserCode());
        res.setName(user.getName());
        res.setEmail(user.getEmail());
        res.setBalance(user.getBalance());
        res.setVersion(user.getVersion());
        res.setCreatedAt(user.getCreatedAt());
        return res;
    }
}
