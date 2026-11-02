package com.servibox.backend.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Plantilla del correo, sin Spring. El cuerpo de muestra es el formato que el outbox guarda desde la tarea 4B. */
class MailRendererTest {

    private static final String BASE = "https://app.example.invalid";
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN_-0";
    private static final String ENLACE = BASE + "/activar#token=" + TOKEN;
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"<>]+");

    private final MailRenderer renderer = new MailRenderer(BASE);

    private static String cuerpo(String contacto, String serviteca, String enlace) {
        return """
                Hola %s:

                La solicitud de %s para usar ServiBox fue aprobada. Para crear el usuario \
                administrador y su contraseña, abre este enlace:

                %s

                El enlace sirve una sola vez y vence el 02/11/2026 a las 20:10 (America/Bogota). Si vence, pide a ServiBox que lo reenvíe.

                Si no solicitaste esta cuenta, ignora este mensaje.
                """.formatted(contacto, serviteca, enlace);
    }

    private static Set<String> enlaces(String texto) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = URL.matcher(HtmlUtils.htmlUnescape(texto));
        while (m.find()) {
            urls.add(m.group());
        }
        return urls;
    }

    @ParameterizedTest
    @EnumSource(OutboxTemplate.class)
    void cadaPlantillaTieneBotonEnlaceYPie(OutboxTemplate plantilla) {
        String enlace = BASE + plantilla.actionPath() + TOKEN;

        RenderedMail mail = renderer.render(plantilla, cuerpo("Ana", "Serviteca Uno", enlace));

        assertThat(mail.html())
                .contains("<a href=\"" + enlace + "\"").contains(plantilla.actionLabel())
                .contains(">" + enlace + "</a>")
                .contains("src=\"cid:" + MailRenderer.LOGO_CID + "\"").contains("alt=\"ServiBox\"").contains(">ServiBox</td>")
                .contains("ServiBox. Ventas, inventario y caja para tu serviteca.").contains(plantilla.reason())
                .contains("href=\"https://servibox.co/politica-de-datos\"").contains("href=\"https://servibox.co/terminos\"")
                .contains("<title>" + plantilla.subject() + "</title>")
                .contains("max-width:600px").contains("Arial,Helvetica")
                .doesNotContain("<script").doesNotContain("<style").doesNotContain("<link").doesNotContain("http://");
        assertThat(mail.html().indexOf(plantilla.actionLabel()))
                .isGreaterThan(mail.html().indexOf("abre este enlace"))
                .isLessThan(mail.html().indexOf("sirve una sola vez"));
    }

    @Test
    void textoPlanoConservaElCuerpoYAgregaElPieConLosMismosEnlaces() {
        String cuerpo = cuerpo("Ana", "Serviteca Uno", ENLACE);

        RenderedMail mail = renderer.render(OutboxTemplate.ACTIVATION, cuerpo);

        assertThat(mail.text()).startsWith(cuerpo.stripTrailing())
                .contains("ServiBox. Ventas, inventario y caja para tu serviteca.")
                .contains(OutboxTemplate.ACTIVATION.reason());
        assertThat(enlaces(mail.text())).isEqualTo(enlaces(mail.html()))
                .containsExactly(ENLACE, "https://servibox.co/politica-de-datos", "https://servibox.co/terminos");
    }

    @Test
    void datosVariablesSalenEscapados() {
        String contacto = "<script>alert(1)</script> & \"Ana\"";
        String serviteca = "<img src=x onerror=alert(2)> Llantas 'Uno'";

        RenderedMail mail = renderer.render(OutboxTemplate.ACTIVATION, cuerpo(contacto, serviteca, ENLACE));

        assertThat(mail.html()).doesNotContain("<script").doesNotContain("<img src=x").doesNotContain("\"Ana\"")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Ana&quot;")
                .contains("&lt;img src=x onerror=alert(2)&gt; Llantas &#39;Uno&#39;");
        assertThat(mail.text()).contains(contacto).contains(serviteca);
    }

    @Test
    void tildesNoSeVuelvenEntidadesYBaseConBarraFinalSirve() {
        RenderedMail mail = new MailRenderer(BASE + "/").render(OutboxTemplate.ACTIVATION,
                cuerpo("Andrés Peña", "Lubricentro Ñandú", ENLACE));

        assertThat(mail.html()).contains("Hola Andrés Peña:").contains("Lubricentro Ñandú").contains("contraseña");
    }

    @Test
    void sinEnlaceReconocibleVaElTextoGuardadoSinCambios() {
        String otroDominio = cuerpo("Ana", "Uno", "https://otro.example.invalid/activar#token=" + TOKEN);
        String tokenCorto = cuerpo("Ana", "Uno", BASE + "/activar#token=corto");
        String conTextoAlLado = cuerpo("Ana", "Uno", "ver " + ENLACE);

        for (String guardado : new String[]{"cuerpo viejo", "", otroDominio, tokenCorto, conTextoAlLado}) {
            RenderedMail mail = renderer.render(OutboxTemplate.ACTIVATION, guardado);
            assertThat(mail.html()).isNull();
            assertThat(mail.text()).isEqualTo(guardado);
        }
    }

    @Test
    void sinUrlBaseConfiguradaNoHayBoton() {
        assertThat(new MailRenderer("").render(OutboxTemplate.ACTIVATION, cuerpo("Ana", "Uno", ENLACE)).html()).isNull();
    }

    @Test
    void unNombreConSaltosDeLineaNoFabricaOtroBoton() {
        String otroEnlace = BASE + "/activar#token=" + "x".repeat(43);
        String deOtroDominio = "https://malo.example.invalid/activar#token=" + TOKEN;

        RenderedMail duplicado = renderer.render(OutboxTemplate.ACTIVATION,
                cuerpo("Ana\n\n" + otroEnlace + "\n\n", "Uno", ENLACE));
        RenderedMail ajeno = renderer.render(OutboxTemplate.ACTIVATION,
                cuerpo("Ana\n\n" + deOtroDominio + "\n\n", "Uno", ENLACE));

        assertThat(duplicado.html()).isNull();
        assertThat(ajeno.html()).contains("<a href=\"" + ENLACE + "\"").doesNotContain("<a href=\"" + deOtroDominio);
    }
}
