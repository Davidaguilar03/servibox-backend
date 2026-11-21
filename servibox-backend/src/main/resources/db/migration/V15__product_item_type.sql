-- Servicios (tarea 59): un servicio es una fila de PRODUCTOS con item_type SERVICIO, como en
-- Autollantas. Sin stock ni costo de compra; comparte el espacio de codigos con los productos.
-- Las filas existentes quedan como PRODUCTO por el DEFAULT.
ALTER TABLE productos ADD COLUMN item_type VARCHAR(20) DEFAULT 'PRODUCTO' NOT NULL;
ALTER TABLE productos ADD CONSTRAINT ck_producto_item_type CHECK (item_type IN ('PRODUCTO', 'SERVICIO'));

-- Listado filtrado por tipo dentro del tenant, en el mismo orden que ix_producto_tenant_descripcion.
CREATE INDEX ix_producto_tenant_tipo ON productos (id_tenant, item_type, descripcion_producto, id_producto);
