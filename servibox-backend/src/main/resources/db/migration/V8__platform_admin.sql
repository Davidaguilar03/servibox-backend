CREATE TABLE administradores_plataforma (
    id_administrador BIGINT PRIMARY KEY,
    email VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_administrador_plataforma_email UNIQUE (email),
    CONSTRAINT ck_administrador_plataforma_email CHECK (email = LOWER(TRIM(email))),
    CONSTRAINT ck_administrador_plataforma_version CHECK (auth_version >= 0)
);
