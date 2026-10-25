-- El precio sugerido y el IVA del costo del producto se guardan en pesos enteros (HALF_UP).
-- Redondea los que ya estan guardados; las columnas siguen en NUMERIC(14,2), ahora con .00.
-- ROUND(NUMERIC, 0) redondea la mitad hacia arriba en PostgreSQL 16 y en H2 (modo PostgreSQL).
-- No toca ninguna otra columna: costos, lineas de venta y compra, totales ni saldos.

UPDATE productos SET precio_sugerido = ROUND(precio_sugerido, 0), iva_producto = ROUND(iva_producto, 0);
