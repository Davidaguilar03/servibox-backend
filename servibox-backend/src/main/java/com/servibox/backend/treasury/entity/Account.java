package com.servibox.backend.treasury.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Cuenta de tesoreria. El nombre es unico dentro de un tenant, no globalmente: dos
 * talleres distintos pueden tener cada uno su "Caja General". Mismo criterio que
 * (id_tenant, username) en User y (id_tenant, codigo_producto) en Product.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_cuenta"))
@Table(
        name = "CUENTAS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cuenta_tenant_name",
                columnNames = {"id_tenant", "nombre_cuenta"}
        )
)
public class Account extends TenantAwareEntity {

    @Column(name = "nombre_cuenta", length = 100, nullable = false)
    private String name;

    /** Saldo con el que arranca la cuenta. No se mueve despues de creada. */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "balance_inicial", precision = 14, scale = 2, nullable = false)
    private Double initialBalance;

    /** Saldo vivo. Solo lo mueve TreasuryService, a traves de los movimientos de la cuenta. */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "saldo_actual", precision = 14, scale = 2, nullable = false)
    private Double currentBalance;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tipo_cuenta", length = 30, nullable = false)
    private AccountType type;
}
