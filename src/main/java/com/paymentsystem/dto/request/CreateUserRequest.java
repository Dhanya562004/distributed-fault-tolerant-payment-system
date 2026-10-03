package com.paymentsystem.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class CreateUserRequest {

    @NotBlank(message = "User code is required")
    private String userCode;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotNull(message = "Initial balance is required")
    @DecimalMin(value = "0.00", message = "Balance cannot be negative")
    private BigDecimal initialBalance;

    public CreateUserRequest() {
    }

    public CreateUserRequest(String userCode, String name, String email, BigDecimal initialBalance) {
        this.userCode = userCode;
        this.name = name;
        this.email = email;
        this.initialBalance = initialBalance;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = userCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }
}
