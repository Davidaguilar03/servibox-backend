package com.servibox.backend.treasury.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Cuenta de tesoreria. El nombre es unico dentro de un tenant, no globalmente: dos
 * talleres distintos pueden tener cada uno su "Caja General". Mismo criterio que
 * (tenant_id, username) en User y (tenant_id, codigo_producto) en Product.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "CUENTAS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cuenta_tenant_name",
                columnNames = {"tenant_id", "nombre_cuenta"}
        )
)
public class Account extends TenantAwareEntity {

    @Column(name = "nombre_cuenta")
    private String name;

    /** Saldo con el que arranca la cuenta. No se mueve despues de creada. */
    @Column(name = "balance_inicial")
    private Double initialBalance;

    /** Saldo vivo. Lo mueven registrarMovimiento y registrarTransferencia. */
    @Column(name = "saldo_actual")
    private Double currentBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_cuenta")
    private AccountType type;
}
