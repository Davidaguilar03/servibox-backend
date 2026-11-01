-- Consentimiento de tratamiento de datos en las solicitudes de registro (tarea 55, Ley 1581 de 2012).
-- Version de la politica aceptada y fecha del servidor en UTC; sin IP ni agente de usuario.
-- Admiten NULL solo por las filas anteriores a esta migracion: un NOT NULL las romperia y un CHECK
-- por fecha o NOT VALID impediria aprobar o rechazar una historica. Toda solicitud nueva las llena
-- en RegistrationRequestService; aqui se impide que una quede sin la otra.
ALTER TABLE solicitudes_registro ADD COLUMN version_politica VARCHAR(10);
ALTER TABLE solicitudes_registro ADD COLUMN fecha_aceptacion_politica TIMESTAMP;
ALTER TABLE solicitudes_registro ADD CONSTRAINT ck_solicitud_politica CHECK (
    (version_politica IS NULL AND fecha_aceptacion_politica IS NULL)
    OR (version_politica IS NOT NULL AND fecha_aceptacion_politica IS NOT NULL));
