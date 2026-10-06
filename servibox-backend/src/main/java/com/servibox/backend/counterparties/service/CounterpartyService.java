package com.servibox.backend.counterparties.service;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import com.servibox.backend.counterparties.repository.CounterpartyRepository;
import com.servibox.backend.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Lo que comparten ventas y compras sobre sus terceros: crearlos, reutilizarlos por
 * documento y exigir que tengan el rol que pide la operacion.
 */
@Service
@RequiredArgsConstructor
public class CounterpartyService {

    private final CounterpartyRepository counterpartyRepository;

    @Transactional(readOnly = true)
    public List<Counterparty> findAll() {
        return counterpartyRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Counterparty> findById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return counterpartyRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<Counterparty> findByDocumentNumber(String documentNumber) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return counterpartyRepository.findByDocumentNumberAndTenantId(documentNumber, tenantId);
    }

    /** Registra un tercero nuevo. Rechaza un documento que ya exista en el tenant. */
    @Transactional
    public Counterparty crear(Counterparty tercero) {
        if (tercero.getRole() == null) {
            throw new InvalidCounterpartyException("El tercero necesita un rol");
        }
        if (tercero.getName() == null || tercero.getName().isBlank()) {
            throw new InvalidCounterpartyException("El tercero necesita un nombre o razon social");
        }
        if (tercero.getDocumentType() == null) {
            throw new InvalidCounterpartyException("El tercero necesita un tipo de documento");
        }
        if (tercero.getDocumentNumber() == null || tercero.getDocumentNumber().isBlank()) {
            throw new InvalidCounterpartyException("El tercero necesita un numero de documento");
        }
        // La base tambien lo impide con uk_tercero_tenant_document, pero como error crudo.
        findByDocumentNumber(tercero.getDocumentNumber())
                .filter(existente -> !existente.getId().equals(tercero.getId()))
                .ifPresent(existente -> {
                    throw new DuplicateCounterpartyDocumentException(tercero.getDocumentNumber());
                });
        return counterpartyRepository.save(tercero);
    }

    /**
     * El tercero de una venta (rol CLIENTE) o de una compra (rol PROVEEDOR), buscado por
     * documento dentro del tenant:
     *
     * * No existe: se crea con el rol pedido.
     * * Existe con el rol contrario: pasa a AMBOS. No se duplica.
     * * Existe con ese rol o con AMBOS: se devuelve tal cual.
     *
     * Correo y celular solo completan los que estaban vacios; nunca pisan un dato que el
     * tercero ya tenia. El nombre y el tipo de documento de uno existente no se tocan.
     */
    @Transactional
    public Counterparty obtenerOCrear(CounterpartyRole rolRequerido,
                                      DocumentType tipoDocumento,
                                      String numeroDocumento,
                                      String nombre,
                                      String correo,
                                      String celular) {
        if (rolRequerido == null || rolRequerido == CounterpartyRole.AMBOS) {
            throw new IllegalArgumentException("El rol requerido es CLIENTE o PROVEEDOR");
        }

        Optional<Counterparty> existente = findByDocumentNumber(numeroDocumento);
        if (existente.isEmpty()) {
            Counterparty nuevo = new Counterparty();
            nuevo.setRole(rolRequerido);
            nuevo.setDocumentType(tipoDocumento);
            nuevo.setDocumentNumber(numeroDocumento);
            nuevo.setName(nombre);
            nuevo.setEmail(correo);
            nuevo.setPhone(celular);
            return crear(nuevo);
        }

        Counterparty tercero = existente.get();
        if (tercero.getRole() != rolRequerido && tercero.getRole() != CounterpartyRole.AMBOS) {
            tercero.setRole(CounterpartyRole.AMBOS);
        }
        if (vacio(tercero.getEmail())) {
            tercero.setEmail(correo);
        }
        if (vacio(tercero.getPhone())) {
            tercero.setPhone(celular);
        }
        return counterpartyRepository.save(tercero);
    }

    /**
     * Una venta exige un tercero CLIENTE o AMBOS; una compra, PROVEEDOR o AMBOS. Se usa
     * cuando la factura referencia un tercero existente por id: ahi no se promueve, se
     * rechaza.
     */
    public void exigirRol(Counterparty tercero, CounterpartyRole rolRequerido) {
        CounterpartyRole rol = tercero.getRole();
        if (rol != rolRequerido && rol != CounterpartyRole.AMBOS) {
            String operacion = rolRequerido == CounterpartyRole.CLIENTE ? "una venta" : "una compra";
            throw new InvalidCounterpartyException("El tercero " + tercero.getDocumentNumber() + " es "
                    + rol + " y " + operacion + " exige " + rolRequerido + " o AMBOS");
        }
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
