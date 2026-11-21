# Changelog

Todos los cambios relevantes de ServiBox (backend) se registran en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el proyecto usa
[Versionado Semántico](https://semver.org/lang/es/).

## [1.1.0] - En desarrollo

### Agregado

- Versionado semántico del producto y endpoint público GET /api/version.
- Servicios: ítems sin stock ni costo, vendibles junto con productos, con categoría Servicios automática por serviteca.
- Reportes de Ventas, Productos y servicios vendidos, Compras, Cartera por cobrar y Cartera por pagar, consultables en vivo con filtros y exportables a PDF y Excel.
- Reportes de Inventario valorizado y rotación, Flujo de caja, Estado de Resultados, IVA y Movimientos consultables en vivo con filtros y exportables a PDF y Excel.

### Cambiado

- La respuesta de productos incluye vatApplies.
- Todos los reportes usan el mes en curso cuando no se indican fechas.
- El filtro por categoría de Compras suma solo las líneas de esa categoría.

## [1.0.0] - 2026-11-01

Primera versión en producción (servibox.co).

### Agregado

- Multiempresa con aislamiento por serviteca: cada consulta filtra por la serviteca de la sesión.
- Solicitud pública de registro de servitecas con consentimiento de tratamiento de datos, aprobación desde la plataforma y activación por correo.
- Inventario de productos con categorías, impuestos, precio sugerido por margen y alertas de stock.
- Ventas de contado y a crédito con abonos, anulación y restauración, con el costo de lo vendido congelado por línea.
- Compras de contado y a crédito con pagos a proveedores.
- Tesorería con cuentas, movimientos manuales (gastos operativos e ingresos ocasionales) y transferencias entre cuentas.
- Reportes de Estado de Resultados y Movimientos, exportables en PDF y Excel.
- Panel de administración de la plataforma: solicitudes de registro, servitecas, usuarios y métricas.
