package com.paymentsystem.service.impl;

import com.paymentsystem.entity.Currency;
import com.paymentsystem.repository.CurrencyRepository;
import com.paymentsystem.service.CurrencyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CurrencyServiceImpl implements CurrencyService {

    private final CurrencyRepository currencyRepository;

    @Autowired
    public CurrencyServiceImpl(CurrencyRepository currencyRepository) {
        this.currencyRepository = currencyRepository;
    }

    @Override
    public Optional<Currency> getCurrency(String code) {
        if (code == null) return Optional.empty();
        return currencyRepository.findById(code.toUpperCase());
    }

    @Override
    public int getDecimalPlaces(String code) {
        return getCurrency(code).map(Currency::getDecimalPlaces).orElse(2);
    }

    @Override
    public boolean isValidCurrency(String code) {
        return getCurrency(code).isPresent();
    }

    @Override
    public List<Currency> getAllCurrencies() {
        return currencyRepository.findAll();
    }
}
