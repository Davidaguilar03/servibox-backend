-- El numero de factura de compra lo pone cada proveedor: dos proveedores pueden expedir el
-- mismo numero. La unicidad pasa de (tenant, numero) a (tenant, proveedor, numero).
-- Compatible con PostgreSQL 16 y con H2 en modo PostgreSQL. No toca datos: toda fila que
-- cumplia la restriccion anterior cumple la nueva.

ALTER TABLE compras DROP CONSTRAINT uk_compra_tenant_invoice_number;

ALTER TABLE compras ADD CONSTRAINT uk_compra_tenant_supplier_invoice_number
    UNIQUE (id_tenant, id_tercero, numero_factura_compra);
