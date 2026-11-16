package com.paymentsystem.service;

import com.paymentsystem.entity.Currency;
import java.util.List;
import java.util.Optional;

public interface CurrencyService {
    Optional<Currency> getCurrency(String code);
    int getDecimalPlaces(String code);
    boolean isValidCurrency(String code);
    List<Currency> getAllCurrencies();
}
