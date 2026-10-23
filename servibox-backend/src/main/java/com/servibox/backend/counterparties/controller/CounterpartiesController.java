package com.servibox.backend.counterparties.controller;

import com.servibox.backend.counterparties.dto.CounterpartyRequest;
import com.servibox.backend.counterparties.dto.CounterpartyResponse;
import com.servibox.backend.counterparties.dto.CounterpartyUpdateRequest;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.counterparties.service.DuplicateCounterpartyDocumentException;
import com.servibox.backend.counterparties.service.InvalidCounterpartyException;
import com.servibox.backend.shared.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/counterparties")
@RequiredArgsConstructor
public class CounterpartiesController {

    private final CounterpartyService counterpartyService;

    /** Terceros del tenant por nombre. Con role, los de ese rol mas los AMBOS. */
    @GetMapping
    public List<CounterpartyResponse> listar(@RequestParam(required = false) CounterpartyRole role) {
        return counterpartyService.listar(role).stream().map(CounterpartyResponse::from).toList();
    }

    /**
     * 201 si el documento es nuevo. Si ya existe con el otro rol pasa a AMBOS por
     * obtenerOCrear y responde 200 con el existente. Con el mismo rol o AMBOS, 409. Sin
     * @Valid: los datos pasan por CounterpartyService.validarDatos, las mismas reglas y
     * mensajes que el PUT.
     */
    @PostMapping
    public ResponseEntity<CounterpartyResponse> crear(@RequestBody CounterpartyRequest request) {
        if (request.role() == null) {
            throw new InvalidCounterpartyException("El tercero necesita un rol");
        }
        if (request.role() == CounterpartyRole.AMBOS) {
            throw new IllegalArgumentException("El rol de un tercero nuevo es CLIENTE o PROVEEDOR");
        }
        CounterpartyService.DatosTercero datos = CounterpartyService.validarDatos(request.name(),
                request.documentType(), request.documentNumber(), request.email(), request.phone());
        Optional<Counterparty> existente = counterpartyService.findByDocumentNumber(datos.numeroDocumento());
        existente.filter(t -> t.getRole() == request.role() || t.getRole() == CounterpartyRole.AMBOS)
                .ifPresent(t -> {
                    throw new DuplicateCounterpartyDocumentException(t.getDocumentNumber(), t.getRole());
                });
        Counterparty tercero = counterpartyService.obtenerOCrear(request.role(), datos.tipoDocumento(),
                datos.numeroDocumento(), datos.nombre(), datos.correo(), datos.celular());
        HttpStatus status = existente.isPresent() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(CounterpartyResponse.from(tercero));
    }

    /** Edita los datos del tercero; el rol no se toca. El Consumidor final es 409. */
    @PutMapping("/{id}")
    public CounterpartyResponse editar(@PathVariable Long id, @RequestBody CounterpartyUpdateRequest request) {
        Counterparty tercero = counterpartyService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tercero no encontrado: " + id));
        return CounterpartyResponse.from(counterpartyService.actualizar(tercero, request.name(),
                request.documentType(), request.documentNumber(), request.email(), request.phone()));
    }
}
