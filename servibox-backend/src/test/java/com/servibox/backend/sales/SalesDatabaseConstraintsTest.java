package com.servibox.backend.sales;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import com.servibox.backend.counterparties.repository.CounterpartyRepository;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.repository.SaleRepository;
import com.servibox.backend.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La validacion del service no reemplaza a la restriccion de base: si alguien escribe por
 * el repositorio directo, la base tiene que seguir frenandolo. Esto prueba que
 * uk_venta_tenant_invoice_number y uk_cliente_tenant_document existen de verdad en el DDL.
 *
 * Es la divergencia deliberada respecto de Autollantas, que valida el numero de factura
 * solo en la aplicacion y no tiene indice unico. Ver 03-DECISIONS.md.
 */
@SpringBootTest
@ActiveProfiles("test")
class SalesDatabaseConstraintsTest {

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private CounterpartyRepository counterpartyRepository;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Sale facturaCruda(String numero) {
        Sale venta = new Sale();
        venta.setInvoiceNumber(numero);
        venta.setInvoiceDate(LocalDate.now());
        venta.setPaymentType(PaymentType.CONTADO);
        venta.setStatus(SaleStatus.PAGADA);
        venta.setSubtotal(0.0);
        venta.setIvaPorPagar(0.0);
        venta.setTotal(0.0);
        return venta;
    }

    private Counterparty clienteCrudo(String documento) {
        Counterparty cliente = new Counterparty();
        cliente.setRole(CounterpartyRole.CLIENTE);
        cliente.setName("Cliente " + documento);
        cliente.setDocumentType(DocumentType.CC);
        cliente.setDocumentNumber(documento);
        return cliente;
    }

    @Test
    void laBaseFrenaDosFacturasConElMismoNumeroAunSaltandoseElService() {
        saleRepository.saveAndFlush(facturaCruda("VEN-00001"));

        assertThatThrownBy(() -> saleRepository.saveAndFlush(facturaCruda("VEN-00001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseFrenaDosClientesConElMismoDocumentoAunSaltandoseElService() {
        counterpartyRepository.saveAndFlush(clienteCrudo("900123456"));

        assertThatThrownBy(() -> counterpartyRepository.saveAndFlush(clienteCrudo("900123456")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
