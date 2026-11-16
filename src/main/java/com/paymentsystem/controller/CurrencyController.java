package com.paymentsystem.controller;

import com.paymentsystem.entity.Currency;
import com.paymentsystem.service.CurrencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/currencies")
@Tag(name = "Currencies", description = "Supported ISO 4217 currencies and precision specifications")
public class CurrencyController {

    private final CurrencyService currencyService;

    public CurrencyController(CurrencyService currencyService) {
        this.currencyService = currencyService;
    }

    @GetMapping
    @Operation(summary = "Get all supported currencies", description = "Returns active ISO-4217 currencies and their minor-unit decimal places.")
    public ResponseEntity<List<Currency>> getAllCurrencies() {
        return ResponseEntity.ok(currencyService.getAllCurrencies());
    }
}
