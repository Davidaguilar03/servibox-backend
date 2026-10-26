-- Catalogos por tenant, nombre (descripcion en productos) e id como desempate.
-- CUENTAS ya tiene UNIQUE (id_tenant, nombre_cuenta): no se duplica ese prefijo.
CREATE INDEX ix_categoria_tenant_nombre ON categoria_productos
    (id_tenant, nombre_categoria_producto, id_categoria_producto);
CREATE INDEX ix_impuesto_tenant_nombre ON tipos_impuesto (id_tenant, nombre_impuesto, id_impuesto);
CREATE INDEX ix_producto_tenant_descripcion ON productos (id_tenant, descripcion_producto, id_producto);
CREATE INDEX ix_tercero_tenant_nombre ON terceros (id_tenant, nombre_razon_social, id_tercero);

-- Recalculo de precios por categoria; FK a categoria no cubierta por la unica de codigo.
CREATE INDEX ix_producto_categoria ON productos (id_categoria_producto);
-- La PK de la tabla puente empieza por categoria; falta el acceso inverso por impuesto.
CREATE INDEX ix_categoria_impuesto_impuesto ON categoria_impuestos (id_impuesto);

-- Listados y rangos de reportes por fecha de negocio. El mismo btree sirve en ambos sentidos.
CREATE INDEX ix_venta_tenant_fecha ON ventas (id_tenant, fecha_venta, id_venta);
CREATE INDEX ix_compra_tenant_fecha ON compras (id_tenant, fecha_compra, id_compra);
-- Referencias a terceros: las unicas existentes empiezan por tenant, no por tercero.
CREATE INDEX ix_venta_tercero ON ventas (id_tercero);
CREATE INDEX ix_compra_tercero ON compras (id_tercero);

-- Detalles por documento y tenant, en orden de captura; tambien cubren las FK al documento.
CREATE INDEX ix_detalle_venta_documento ON detalle_ventas (id_venta, id_tenant, id_detalle_venta);
CREATE INDEX ix_detalle_compra_documento ON detalle_compras (id_compra, id_tenant, id_detalle_compra);
-- Existencia de facturas por producto y ultima compra para revertir/recuperar costo.
CREATE INDEX ix_detalle_venta_producto ON detalle_ventas (id_producto, id_tenant);
CREATE INDEX ix_detalle_compra_producto ON detalle_compras
    (id_producto, id_tenant, id_compra, id_detalle_compra);

-- Libro por tenant/rango y extracto por cuenta/rango, con id para desempatar.
CREATE INDEX ix_movimiento_tenant_fecha ON movimientos (id_tenant, fecha_movimiento, id_movimiento);
CREATE INDEX ix_movimiento_cuenta_fecha ON movimientos
    (id_cuenta, id_tenant, fecha_movimiento, id_movimiento);
-- Pendientes, anulacion y restauracion: todos los movimientos por documento, activos o no.
CREATE INDEX ix_movimiento_venta ON movimientos (id_venta, id_tenant, id_movimiento);
CREATE INDEX ix_movimiento_compra ON movimientos (id_compra, id_tenant, id_movimiento);
