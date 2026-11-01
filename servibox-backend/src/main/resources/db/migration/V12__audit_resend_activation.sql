-- Auditoria del reenvio de activacion (tarea 51). El reenvio afecta a una solicitud de
-- registro, que aun no tiene tenant: id_tenant_objetivo pasa a admitir NULL y la solicitud va
-- en id_solicitud_objetivo. Como el tenant y el usuario de V11, sin FK a proposito: el
-- registro debe sobrevivir a un borrado futuro de la solicitud. Las filas existentes son
-- todas acciones sobre tenants: quedan con su tenant y sin solicitud, y cumplen el CHECK nuevo.
ALTER TABLE auditoria_plataforma ADD COLUMN id_solicitud_objetivo VARCHAR(36);
ALTER TABLE auditoria_plataforma ALTER COLUMN id_tenant_objetivo DROP NOT NULL;

ALTER TABLE auditoria_plataforma DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria_plataforma ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN
    ('SUSPEND_TENANT', 'REACTIVATE_TENANT', 'REVOKE_USER_SESSIONS', 'REVOKE_TENANT_SESSIONS',
     'RESEND_ACTIVATION'));

-- El reenvio apunta solo a la solicitud; las acciones sobre tenants, solo al tenant.
ALTER TABLE auditoria_plataforma ADD CONSTRAINT ck_auditoria_objetivo CHECK (
    (accion = 'RESEND_ACTIVATION' AND id_solicitud_objetivo IS NOT NULL
        AND id_tenant_objetivo IS NULL AND id_usuario_objetivo IS NULL)
    OR (accion <> 'RESEND_ACTIVATION' AND id_tenant_objetivo IS NOT NULL AND id_solicitud_objetivo IS NULL));
