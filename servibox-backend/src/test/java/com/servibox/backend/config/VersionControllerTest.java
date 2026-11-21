package com.servibox.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionPublicaSinTokenConSoloNombreVersionYFecha() throws Exception {
        String buildTime = mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.name").value("ServiBox"))
                // La suite corre con Maven, que genera build-info en generate-resources.
                .andExpect(jsonPath("$.version").value("1.1.0"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"buildTime\":\"([^\"]+)\".*", "$1");
        assertThat(Instant.parse(buildTime)).isNotNull();
    }

    @Test
    void versionNoAbreOtrosMetodosNiSubrutas() throws Exception {
        mockMvc.perform(post("/api/version")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/version/details")).andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinBuildPropertiesRespondeDev() {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        VersionController.VersionResponse respuesta = new VersionController(provider).version();

        assertThat(respuesta.name()).isEqualTo("ServiBox");
        assertThat(respuesta.version()).isEqualTo("dev");
        assertThat(respuesta.buildTime()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void conBuildPropertiesUsaSuVersionYFechaIso() {
        Properties props = new Properties();
        props.setProperty("version", "9.8.7");
        props.setProperty("time", "2026-11-21T15:30:00Z");
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(props));

        VersionController.VersionResponse respuesta = new VersionController(provider).version();

        assertThat(respuesta.version()).isEqualTo("9.8.7");
        assertThat(respuesta.buildTime()).isEqualTo("2026-11-21T15:30:00Z");
    }
}
