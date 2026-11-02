# Correo con la marca de ServiBox (tarea 57), 2026-11-01

Solo backend. Rama feat/57-branded-email-templates creada desde main en 8858b80 (merge PR 56), igual a
origin/main tras fetch. Los dos .idea ajenos quedan fuera. Sin commit, push, PR ni despliegue. Frontend
solo leido: src/brand/symbol.svg, src/styles/tokens.css y docs/ai-context/06-COPY.md. Decision en
[03-DECISIONS](../03-DECISIONS.md).

## Correos que envia el backend

Solo uno. `OutboxTemplate` tiene un valor, el CHECK `ck_correo_plantilla` solo admite ACTIVATION y el
unico llamador de `MailOutbox.enqueue` es `RegistrationActivationService.issue`.

| Plantilla | Asunto | Destinatario | Cuando |
|-|-|-|-|
| ACTIVATION | Activa tu cuenta de ServiBox | correo del responsable de la solicitud | al aprobar y al reenviar la activacion |

No existen correos de solicitud recibida, rechazo ni restablecer contrasena.

## Implementado

* `MailRenderer`: al enviar, convierte el cuerpo guardado en texto (cuerpo mas pie) y HTML (tablas,
  estilos en linea, Arial/Helvetica, 600 px maximo, tabla condicional para Outlook, colores explicitos,
  color-scheme light, boton con mso-padding-alt, enlace escrito debajo, logo por cid, alt y nombre como
  texto, pie con enlaces). Todo lo variable pasa por `HtmlUtils.htmlEscape`.
* `OutboxTemplate`: agrega boton, ruta del enlace y motivo del pie. Asunto sin cambios.
* `MailTransport.send(to, subject, text, html)`, html null es solo texto.
* `SmtpMailTransport`: alternative(texto, related(html, logo)), UTF-8. Lee el logo al arrancar.
* `OutboxWorker`: renderiza antes de enviar; sin enlace reconocible avisa en el log solo con el id.
* `resources/mail/servibox-logo.png`: 96x96, 1990 bytes, fondo transparente, rasterizado de symbol.svg
  con Edge sin ventana y recortado con Java (ImageIO). Rojo verificado por pixel: #ff3b30. Se muestra a
  40 px. El frontend no tiene logotipo en SVG, solo el simbolo.

Textos nuevos, los unicos: `Activar mi cuenta`, `Si el botón no funciona, copia este enlace en tu
navegador:`, `Recibes este correo porque se solicitó una cuenta de ServiBox con esta dirección.`,
`Política de datos`, `Términos` y el lema pedido. El cuerpo no cambia.

Sin mensajes de error nuevos para el frontend. Interno de arranque nuevo:
`No se pudo leer el logo del correo`. Log nuevo: `Outbox: correo {id} sin enlace reconocible, va solo en texto`.

## Outbox

Guarda destinatario, plantilla y el cuerpo en texto plano cifrado (AES-256-GCM, Base64, 4000 caracteres
como maximo), solo mientras esta PENDING. El asunto sale del enum. Sin migracion, sin cambio de formato
y sin cambio de cifrado. El texto del cuerpo no ha cambiado desde la tarea 4B, asi que una fila pendiente
de produccion tiene este mismo formato y sale con la marca y su mismo enlace. No se pudo consultar
produccion para saber si hay filas pendientes. Las filas SENT no se reenvian.

## Pruebas

Conteos por motor; no se suman.

* JUnit H2, suite completa con build: 745 = 732 base + 13 nuevas, cero fallos, errores u omitidas,
  05:01 min. El 732 es derivado (745 menos 13): la base no se corrio aparte.
* Nuevas: MailRendererTest 7, SmtpMailTransportMimeTest 3 (serializa y vuelve a leer el MIME),
  OutboxLegacyFormatTest 2 (filas insertadas por SQL), 1 en OutboxLogPrivacyTest.
* Ajustadas: FakeMailTransport y la lambda de OutboxLogPrivacyTest (firma nueva con html);
  RegistrationActivationTest agrega aserciones de HTML y pie al ciclo completo, sin retirar ninguna.
* Primera corrida focalizada: 39 con 1 fallo de la prueba nueva de logs, que exigia que no apareciera la
  ruta del enlace. El log traia `token=[oculto]`, sin fuga; se corrigio la prueba para exigir ese texto.
* PostgreSQL 16: no corrido. El repo no usa Testcontainers y esta tarea no cambia SQL ni esquema.
* HTTP real: jar empaquetado en dev, puerto 18089, con SMTP_HOST configurado a un puerto cerrado. Arranca,
  el logo esta en BOOT-INF/classes/mail, no aparece el aviso de outbox sin transporte y activar responde
  404 con la compuerta apagada. No cubre un envio de correo.
* SMTP: no hay GreenMail ni otro servidor de pruebas en el repo, asi que no hubo envio por SMTP de
  prueba. Tampoco correos reales a Resend.
* Vista previa en Edge sin ventana (iframe de 600, 390 y 320 px; capturas revisadas a 600 y 390):
  scrollWidth igual al ancho en todos los casos, con nombres largos tambien; logo 96x96 mostrado a 40.
  Sin imagen se lee el alt y el nombre. `<b>` en un nombre sale como texto. Oscuro forzado de Chromium
  legible. Los motores de Gmail y Outlook no se probaron.
* `git diff --check` sin hallazgos. No hay lint backend.

## Limitaciones y riesgos

* No verificado con Resend: si conserva la imagen en linea por SMTP. Si no, se lee el nombre en texto.
* El seguimiento de clics o aperturas de Resend, si se activa, modificaria el HTML y el enlace con token.
* Outlook de escritorio: esquinas rectas. Modo oscuro: cada cliente decide; Gmail movil puede invertir.
* Un nombre con saltos de linea puede crear parrafos, igual que en el texto de hoy, siempre escapados.
  El registro no rechaza saltos de linea en los nombres.
* Cambiar ACTIVATION_BASE_URL con correos pendientes los deja solo en texto, que siguen funcionando con
  el enlace que llevan.
