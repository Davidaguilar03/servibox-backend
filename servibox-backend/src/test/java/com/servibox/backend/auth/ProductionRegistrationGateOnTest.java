package com.servibox.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Prod con la compuerta encendida y REGISTRATION_ENABLED=false: el inmediato sigue en 404 (no 403). */
@TestPropertySource(properties = {"servibox.registration-requests.enabled=true", "servibox.registration.enabled=false"})
class ProductionRegistrationGateOnTest extends ProductionProfileTestBase {

    @Test
    void registroInmediatoSigueCerrado() throws Exception {
        registroInmediatoResponde404SinCrearNada();
    }
}
