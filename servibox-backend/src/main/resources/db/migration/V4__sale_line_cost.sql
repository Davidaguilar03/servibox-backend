-- Costo unitario de lo vendido, congelado por linea al facturar (como iva_generado_linea):
-- la utilidad bruta pasa a ser ventas sin IVA menos costo de lo vendido.
-- Compatible con PostgreSQL 16 y con H2 en modo PostgreSQL.
--
-- Las lineas que ya existen no guardaron su costo: se rellenan con el costo actual del
-- producto (un costo nulo cuenta como 0). Es una aproximacion: si el costo cambio despues
-- de la venta, la linea queda con el costo de hoy y no con el de su momento.

ALTER TABLE detalle_ventas ADD COLUMN costo_unitario_linea NUMERIC(14, 2);

UPDATE detalle_ventas SET costo_unitario_linea = COALESCE(
    (SELECT p.costo_compra FROM productos p WHERE p.id_producto = detalle_ventas.id_producto), 0);

ALTER TABLE detalle_ventas ALTER COLUMN costo_unitario_linea SET NOT NULL;

ALTER TABLE detalle_ventas ADD CONSTRAINT ck_detalle_venta_costo CHECK (costo_unitario_linea >= 0);
