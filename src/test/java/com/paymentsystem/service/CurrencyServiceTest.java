package com.paymentsystem.service;

import com.paymentsystem.entity.Currency;
import com.paymentsystem.repository.CurrencyRepository;
import com.paymentsystem.service.impl.CurrencyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrencyServiceTest {

    @Mock
    private CurrencyRepository currencyRepository;

    private CurrencyService currencyService;

    @BeforeEach
    void setUp() {
        currencyService = new CurrencyServiceImpl(currencyRepository);
    }

    @Test
    @DisplayName("getCurrency: returns Currency entity when present")
    void testGetCurrency_Found() {
        Currency usd = new Currency("USD", 2, "US Dollar");
        when(currencyRepository.findById("USD")).thenReturn(Optional.of(usd));

        Optional<Currency> result = currencyService.getCurrency("usd");
        assertTrue(result.isPresent());
        assertEquals("USD", result.get().getCode());
        assertEquals(2, result.get().getDecimalPlaces());
        assertEquals("US Dollar", result.get().getName());
    }

    @Test
    @DisplayName("getCurrency: returns empty when null or absent")
    void testGetCurrency_NotFoundOrNull() {
        when(currencyRepository.findById("JPY")).thenReturn(Optional.empty());

        assertTrue(currencyService.getCurrency(null).isEmpty());
        assertTrue(currencyService.getCurrency("JPY").isEmpty());
    }

    @Test
    @DisplayName("getDecimalPlaces: returns configured decimal places or default 2")
    void testGetDecimalPlaces() {
        Currency inr = new Currency("INR", 2, "Indian Rupee");
        when(currencyRepository.findById("INR")).thenReturn(Optional.of(inr));
        when(currencyRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertEquals(2, currencyService.getDecimalPlaces("INR"));
        assertEquals(2, currencyService.getDecimalPlaces("UNKNOWN"));
    }

    @Test
    @DisplayName("isValidCurrency: validates against repository")
    void testIsValidCurrency() {
        when(currencyRepository.findById("EUR")).thenReturn(Optional.of(new Currency("EUR", 2, "Euro")));
        when(currencyRepository.findById("XYZ")).thenReturn(Optional.empty());

        assertTrue(currencyService.isValidCurrency("EUR"));
        assertTrue(currencyService.isValidCurrency("eur"));
        assertFalse(currencyService.isValidCurrency("XYZ"));
        assertFalse(currencyService.isValidCurrency(null));
    }

    @Test
    @DisplayName("getAllCurrencies: retrieves all supported currencies")
    void testGetAllCurrencies() {
        List<Currency> currencies = List.of(
                new Currency("USD", 2, "US Dollar"),
                new Currency("EUR", 2, "Euro"),
                new Currency("GBP", 2, "British Pound"),
                new Currency("INR", 2, "Indian Rupee")
        );
        when(currencyRepository.findAll()).thenReturn(currencies);

        List<Currency> result = currencyService.getAllCurrencies();
        assertEquals(4, result.size());
    }
}
