package com.paymentsystem.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Currency entity representing supported ISO 4217 currencies and their minor-unit decimal places.
 * Examples:
 * USD -> 2 decimal places (1 USD = 100 cents)
 * EUR -> 2 decimal places (1 EUR = 100 cents)
 * GBP -> 2 decimal places (1 GBP = 100 pence)
 * INR -> 2 decimal places (1 INR = 100 paise)
 */
@Entity
@Table(name = "currencies")
public class Currency {

    @Id
    @Column(name = "code", length = 8, nullable = false)
    private String code;

    @Column(name = "decimal_places", nullable = false)
    private Integer decimalPlaces = 2;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    public Currency() {
    }

    public Currency(String code, Integer decimalPlaces, String name) {
        this.code = code;
        this.decimalPlaces = decimalPlaces;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getDecimalPlaces() {
        return decimalPlaces;
    }

    public void setDecimalPlaces(Integer decimalPlaces) {
        this.decimalPlaces = decimalPlaces;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
