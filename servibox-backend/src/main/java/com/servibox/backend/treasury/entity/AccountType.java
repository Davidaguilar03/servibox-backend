package com.servibox.backend.treasury.entity;

/**
 * Tipos de cuenta del diccionario. Autollantas solo tenia efectivo y banco (CASH y BANK);
 * DIGITAL cubre billeteras como Nequi o Daviplata.
 */
public enum AccountType {
    EFECTIVO,
    BANCO,
    DIGITAL
}
