package com.servibox.backend.reporting.export;

/** Reportes que se pueden exportar con GET /api/reporting/export, con el nombre base del archivo. */
public enum ReportType {
    ESTADO_RESULTADOS("estado-resultados"),
    MOVIMIENTOS("movimientos-cuentas"),
    VENTAS("ventas"),
    PRODUCTOS_VENDIDOS("productos-servicios-vendidos"),
    COMPRAS("compras"),
    CARTERA_COBRAR("cartera-por-cobrar"),
    CARTERA_PAGAR("cartera-por-pagar"),
    INVENTARIO("inventario-rotacion"),
    FLUJO_CAJA("flujo-caja"),
    IVA("reporte-iva");

    private final String archivo;

    ReportType(String archivo) {
        this.archivo = archivo;
    }

    public String archivo() {
        return archivo;
    }

    /** Las carteras son una foto al dia de hoy: no usan desde ni hasta. */
    public boolean esCorte() {
        return this == CARTERA_COBRAR || this == CARTERA_PAGAR;
    }
}
