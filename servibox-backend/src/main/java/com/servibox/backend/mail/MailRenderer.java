package com.servibox.backend.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Convierte al enviar el cuerpo guardado en el outbox (texto plano, sin cambio de formato ni de
 * columna) en el correo con la marca: asi lo ya encolado tambien sale bien. Los parrafos son
 * bloques separados por linea en blanco; el boton sale del unico bloque que es exactamente un
 * enlace de la plantilla bajo ACTIVATION_BASE_URL. Los nombres del cuerpo los escribe quien
 * solicita y pueden traer saltos de linea: sin ese enlace unico no se arma boton, el correo va
 * solo con el texto guardado. Todo lo variable sale escapado. No registra nada.
 */
@Component
public class MailRenderer {

    static final String LOGO_CID = "servibox-logo@servibox.co";

    private static final String TAGLINE = "ServiBox. Ventas, inventario y caja para tu serviteca.";
    private static final String POLICY_URL = "https://servibox.co/politica-de-datos";
    private static final String TERMS_URL = "https://servibox.co/terminos";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final String FONT = "font-family:Arial,Helvetica,sans-serif;";
    private static final String CANVAS = "#f2f3f5";
    private static final String SURFACE = "#ffffff";
    private static final String NAV = "#1f2937";
    private static final String TEXT = "#1f2937";
    private static final String MUTED = "#5f6673";
    // brand-ink del frontend: blanco sobre brand (#ff3b30) no llega a 4.5 a 1.
    private static final String ACTION = "#d32316";

    private final String baseUrl;

    public MailRenderer(@Value("${servibox.activation.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public RenderedMail render(OutboxTemplate template, String body) {
        String[] blocks = body.strip().split("\\R\\s*\\R");
        List<String> links = new ArrayList<>();
        for (String block : blocks) {
            if (isActionLink(template, block)) {
                links.add(block);
            }
        }
        if (links.size() != 1) {
            return new RenderedMail(body, null);
        }
        return new RenderedMail(text(template, body), html(template, blocks, links.getFirst()));
    }

    private boolean isActionLink(OutboxTemplate template, String block) {
        String prefix = baseUrl + template.actionPath();
        return !baseUrl.isEmpty() && block.startsWith(prefix)
                && TOKEN.matcher(block.substring(prefix.length())).matches();
    }

    private static String text(OutboxTemplate template, String body) {
        return body.stripTrailing() + "\n\n" + TAGLINE + "\n" + template.reason()
                + "\nPolítica de datos: " + POLICY_URL + "\nTérminos: " + TERMS_URL + "\n";
    }

    private static String html(OutboxTemplate template, String[] blocks, String link) {
        StringBuilder content = new StringBuilder();
        for (String block : blocks) {
            content.append(block.equals(link) ? action(template, link) : paragraph(block));
        }
        return "<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta name=\"color-scheme\" content=\"light\">\n"
                + "<meta name=\"supported-color-schemes\" content=\"light\">\n"
                + "<title>" + esc(template.subject()) + "</title>\n</head>\n"
                + "<body style=\"margin:0;padding:0;background-color:" + CANVAS + ";\" bgcolor=\"" + CANVAS + "\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " bgcolor=\"" + CANVAS + "\" style=\"background-color:" + CANVAS + ";\"><tr>"
                + "<td align=\"center\" style=\"padding:24px 12px;\">\n"
                + "<!--[if mso]><table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\""
                + " border=\"0\"><tr><td><![endif]-->\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"width:100%;max-width:600px;\">\n"
                + header()
                + "<tr><td bgcolor=\"" + SURFACE + "\" style=\"background-color:" + SURFACE
                + ";border-radius:0 0 8px 8px;padding:28px 24px;\">\n" + content + "</td></tr>\n"
                + footer(template)
                + "</table>\n<!--[if mso]></td></tr></table><![endif]-->\n</td></tr></table>\n</body>\n</html>\n";
    }

    private static String header() {
        return "<tr><td bgcolor=\"" + NAV + "\" style=\"background-color:" + NAV
                + ";border-radius:8px 8px 0 0;padding:16px 24px;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td style=\"padding-right:12px;\"><img src=\"cid:" + LOGO_CID
                + "\" alt=\"ServiBox\" width=\"40\" height=\"40\" style=\"display:block;border:0;width:40px;height:40px;"
                + FONT + "font-size:12px;color:#ffffff;\"></td>"
                + "<td style=\"" + FONT + "font-size:22px;line-height:28px;font-weight:bold;color:#ffffff;\">ServiBox</td>"
                + "</tr></table></td></tr>\n";
    }

    private static String paragraph(String block) {
        return "<p style=\"margin:0 0 16px 0;" + FONT + "font-size:16px;line-height:24px;color:" + TEXT + ";\">"
                + esc(block) + "</p>\n";
    }

    /** Boton a prueba de balas: fondo en la celda, y mso-padding-alt da el relleno que Outlook ignora en el enlace. */
    private static String action(OutboxTemplate template, String link) {
        String href = esc(link);
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"margin:8px 0 16px 0;\"><tr>"
                + "<td align=\"center\" bgcolor=\"" + ACTION + "\" style=\"background-color:" + ACTION
                + ";border-radius:6px;mso-padding-alt:14px 28px;\">"
                + "<a href=\"" + href + "\" target=\"_blank\" style=\"display:inline-block;padding:14px 28px;" + FONT
                + "font-size:16px;line-height:20px;font-weight:bold;color:#ffffff;text-decoration:none;\">"
                + esc(template.actionLabel()) + "</a></td></tr></table>\n"
                + "<p style=\"margin:0 0 16px 0;" + FONT + "font-size:13px;line-height:20px;color:" + MUTED + ";\">"
                + "Si el botón no funciona, copia este enlace en tu navegador:<br>"
                + "<a href=\"" + href + "\" target=\"_blank\" style=\"color:" + ACTION + ";word-break:break-all;\">"
                + href + "</a></p>\n";
    }

    private static String footer(OutboxTemplate template) {
        String small = FONT + "font-size:12px;line-height:18px;color:" + MUTED + ";";
        return "<tr><td style=\"padding:20px 24px 0 24px;\">"
                + "<p style=\"margin:0 0 6px 0;" + small + "\">" + esc(TAGLINE) + "</p>"
                + "<p style=\"margin:0 0 6px 0;" + small + "\">" + esc(template.reason()) + "</p>"
                + "<p style=\"margin:0;" + small + "\">"
                + "<a href=\"" + POLICY_URL + "\" target=\"_blank\" style=\"color:" + MUTED + ";\">Política de datos</a>"
                + " &middot; "
                + "<a href=\"" + TERMS_URL + "\" target=\"_blank\" style=\"color:" + MUTED + ";\">Términos</a>"
                + "</p></td></tr>\n";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
