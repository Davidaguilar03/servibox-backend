-- Registro publico de la serviteca: NIT y telefono del tenant y nombre completo del usuario.
-- Compatible con PostgreSQL 16 y con H2 en modo PostgreSQL. Las tres son NULL: los tenants
-- y usuarios que ya existian (incluidos los de la siembra dev) quedan sin ese dato.

ALTER TABLE tenants ADD COLUMN nit_tenant VARCHAR(25);
ALTER TABLE tenants ADD COLUMN telefono_tenant VARCHAR(25);

ALTER TABLE usuarios ADD COLUMN nombre_completo VARCHAR(150);
