package com.paymentsystem.controller;

import com.paymentsystem.dto.request.CreateUserRequest;
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

    @Autowired
    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping
    @Operation(summary = "Create User Account", description = "Provisions a new user account with initial balance")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = new User(request.getUserCode(), request.getName(), request.getEmail(), request.getInitialBalance());
        User saved = userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @GetMapping
    @Operation(summary = "List All Users", description = "Retrieves all registered user accounts")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = userRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
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
