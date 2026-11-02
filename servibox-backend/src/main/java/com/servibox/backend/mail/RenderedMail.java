package com.servibox.backend.mail;

/** Lo que se entrega al transporte. html null: el correo va solo en texto plano. */
public record RenderedMail(String text, String html) {
}
