package com.servibox.backend.reporting.controller;

import com.servibox.backend.reporting.dto.IvaReportResponse;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.dto.MovementSummaryResponse;
import com.servibox.backend.reporting.service.ReportingService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Solo JSON. Autollantas genera PDF y Excel; aqui no, ver 03-DECISIONS.md.
 *
 * desde / hasta son fechas ISO (2026-09-01). Van opcionales en la firma para que, si
 * faltan, respondan 400 con el mensaje del service y no el 500 generico.
 */
@RestController
@RequestMapping("/api/reporting")
@RequiredArgsConstructor
public class ReportingController {

    private final ReportingService reportingService;

    /** Sin desde ni hasta: KPIs globales. Con los dos: KPIs del periodo. */
    @GetMapping("/kpis")
    public KpisResponse kpis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        if (desde == null && hasta == null) {
            return reportingService.getGlobalKpis();
        }
        return reportingService.getPeriodKpis(desde, hasta);
    }

    @GetMapping("/movements")
    public List<MovementSummaryResponse> movements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportingService.getMovements(desde, hasta);
    }

    @GetMapping("/iva")
    public IvaReportResponse iva(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportingService.buildReporteIva(desde, hasta);
    }
}
