-- Solicitudes de registro (tarea 4A). Tabla global, sin tenant y sin contrasena.
-- La reserva vigente del slug vive en slug_reservado: igual al slug en PENDING y APPROVED,
-- NULL en REJECTED y CANCELLED. Su UNIQUE (NULL no choca) equivale al indice unico parcial
-- sobre estado, que H2 no admite; el CHECK impide que la columna y el estado se separen.
CREATE TABLE solicitudes_registro (
    id_solicitud VARCHAR(36) PRIMARY KEY,
    nombre_comercial VARCHAR(150) NOT NULL,
    nit VARCHAR(25) NOT NULL,
    telefono VARCHAR(25) NOT NULL,
    nombre_responsable VARCHAR(150) NOT NULL,
    correo_responsable VARCHAR(150) NOT NULL,
    correo_normalizado VARCHAR(150) NOT NULL,
    slug_solicitado VARCHAR(30) NOT NULL,
    slug_reservado VARCHAR(30),
    estado VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    clave_idempotencia VARCHAR(100),
    hash_cuerpo VARCHAR(64) NOT NULL,
    idempotencia_expira_en TIMESTAMP NOT NULL,
    fecha_creacion TIMESTAMP NOT NULL,
    reserva_expira_en TIMESTAMP NOT NULL,
    fecha_revision TIMESTAMP,
    id_revisor BIGINT,
    motivo_rechazo VARCHAR(500),
    CONSTRAINT uk_solicitud_slug_reservado UNIQUE (slug_reservado),
    CONSTRAINT uk_solicitud_clave_idempotencia UNIQUE (clave_idempotencia),
    CONSTRAINT fk_solicitud_revisor FOREIGN KEY (id_revisor)
        REFERENCES administradores_plataforma (id_administrador) ON DELETE RESTRICT,
    CONSTRAINT ck_solicitud_estado CHECK (estado IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_solicitud_reserva CHECK (
        (estado IN ('PENDING', 'APPROVED') AND slug_reservado IS NOT NULL AND slug_reservado = slug_solicitado)
        OR (estado IN ('REJECTED', 'CANCELLED') AND slug_reservado IS NULL)),
    CONSTRAINT ck_solicitud_slug CHECK (slug_solicitado = LOWER(TRIM(slug_solicitado))),
    CONSTRAINT ck_solicitud_correo CHECK (correo_normalizado = LOWER(TRIM(correo_normalizado))),
    CONSTRAINT ck_solicitud_rechazo CHECK (estado <> 'REJECTED' OR motivo_rechazo IS NOT NULL),
    CONSTRAINT ck_solicitud_version CHECK (version >= 0)
);

CREATE INDEX idx_solicitud_estado_fecha ON solicitudes_registro (estado, fecha_creacion);
CREATE INDEX idx_solicitud_nit ON solicitudes_registro (nit);
CREATE INDEX idx_solicitud_correo ON solicitudes_registro (correo_normalizado);
