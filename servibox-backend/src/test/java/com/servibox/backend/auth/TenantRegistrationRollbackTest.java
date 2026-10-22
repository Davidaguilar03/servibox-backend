package com.servibox.backend.auth;

import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.repository.CounterpartyRepository;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.repository.ProductCategoryRepository;
import com.servibox.backend.inventory.repository.TaxTypeRepository;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.repository.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Un fallo en el ultimo paso del aprovisionamiento (el Consumidor final) deshace todo lo
 * anterior: tenant, administrador, IVA, categorias y cuenta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantRegistrationRollbackTest {

    private static final String CUERPO = """
            {"businessName":"Serviteca Fallida","nit":"900123456-7","phone":"3001234567",
             "slug":"taller-fallido","adminFullName":"Ana Maria Rojas","adminEmail":"ana@fallido.co",
             "adminUsername":"admin","password":"clave-segura-1","acceptTerms":true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private CounterpartyService counterpartyService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private TaxTypeRepository taxTypeRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CounterpartyRepository counterpartyRepository;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private List<Long> conteos() {
        return List.of(tenantRepository.count(), userRepository.count(), categoryRepository.count(),
                taxTypeRepository.count(), accountRepository.count(), counterpartyRepository.count());
    }

    @Test
    void unFalloAMitadDelAprovisionamientoNoDejaNadaDelTenant() throws Exception {
        List<Long> antes = conteos();
        doThrow(new RuntimeException("fallo controlado")).when(counterpartyService).consumidorFinal();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error interno del servidor"))
                .andExpect(jsonPath("$.token").doesNotExist());

        assertThat(conteos()).isEqualTo(antes);
        assertThat(tenantRepository.findBySlug("taller-fallido")).isEmpty();
        assertThat(TenantContext.getTenantId()).isNull();

        // Sin el fallo, el mismo slug se registra: no quedo nada que lo ocupe.
        reset(counterpartyService);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isCreated());
    }
}
