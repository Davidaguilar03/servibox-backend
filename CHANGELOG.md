# Changelog

Todos los cambios relevantes de ServiBox (backend) se registran en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el proyecto usa
[Versionado Semántico](https://semver.org/lang/es/).

## [1.1.0] - En desarrollo

### Agregado

- Versionado semántico del producto y endpoint público GET /api/version.

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
