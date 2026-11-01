# Cache y cabeceras de seguridad del frontend en Caddy (tarea 56), 2026-11-01

Solo backend, sin Java ni migraciones. Rama chore/56-caddy-cache-headers creada desde main en 0ae2b5b
(merge PR 55), igual a origin/main tras fetch. Los dos .idea ajenos quedan fuera. Sin commit, push, PR
ni despliegue. Docker estaba apagado: David lo encendio para la validacion.

## Implementado

Decision en [03-DECISIONS](../03-DECISIONS.md); operacion en [VPS.md](../../deploy/VPS.md), secciones 7
y 11.

* `deploy/Caddyfile`, solo el bloque del frontend. `no-cache` y las cuatro cabeceras de seguridad a
  nivel de sitio. `handle /assets/*` con `file_server` sin `try_files`, y `immutable` solo con el
  matcher `file`. `handle` general con el fallback SPA.
* Sin mensajes de error nuevos.

## Pruebas

Sin suite JUnit: no cambia codigo. Caddy v2.11.7 (imagen `caddy:2` del compose).

`caddy validate` del Caddyfile real: `Valid configuration`, salida 0.

Prueba con el Caddyfile byte a byte igual al real (`cmp`), cambiando solo los dominios por variables:
`FRONTEND_DOMAIN=:8080` sin TLS y luego `FRONTEND_DOMAIN=localhost` con la CA interna. Sitio de prueba
con index.html, assets/app.abc123.js, favicon.ico y robots.txt. Esperado frente a obtenido, igual en las
dos corridas salvo la compresion y los casos marcados, medidos solo sin TLS:

| Ruta | Estado | Cache-Control | Tipo |
|-|-|-|-|
| /assets/app.abc123.js | 200 | public, max-age=31536000, immutable | text/javascript |
| / | 200 | no-cache | text/html |
| /login | 200, cuerpo de index.html | no-cache | text/html |
| /assets/no-existe.js | 404, 0 bytes, sin index.html | no-cache | ninguno |
| /favicon.ico (sin TLS) | 200 | no-cache | image/vnd.microsoft.icon |
| /robots.txt (sin TLS) | 200 | no-cache | text/plain |
| /assets/ (sin TLS) | 404 | no-cache | ninguno |
| /assets/sub/no.js (sin TLS) | 404 | no-cache | ninguno |
| /assets sin barra (sin TLS) | 200, fallback SPA | no-cache | text/html |

* HSTS, nosniff, Referrer-Policy y X-Frame-Options presentes en todas las filas, 404 incluidos.
* Compresion, bytes del cuerpo sin comprimir, gzip y zstd: `/` 1684, 125 y 121; asset 4000, 72 y 54.
* Revalidacion: `If-None-Match` con el ETag de `/` da 304, con `no-cache` y cabeceras de seguridad.
* API: la configuracion adaptada de la ruta de api.servibox.co es identica antes y despues, y `listen`
  no cambia. El unico hunk del diff es el del frontend. No se probo contra un backend en marcha.
* Log de Caddy: 0 lineas de nivel error.

Montaje del Caddyfile: en un disco de la VM Linux de Docker, un archivo suelto reemplazado por otro
sigue mostrando el contenido viejo (v1) hasta `docker restart`, que muestra v2. Con `git checkout` y
`mount --bind` en un contenedor pasa lo mismo: el inode cambia y el montaje sigue en v1. Sobre la
carpeta de Windows no se reproduce, porque Docker Desktop muestra el archivo nuevo de inmediato.

Fallos del arnes, no del producto: la primera corrida tuvo el sitio vacio porque el script que creaba
los archivos fallo sin avisar (todo 404, descartada), y los tamanos de la segunda salieron en 0 por
`-o /dev/null` con `MSYS_NO_PATHCONV=1`; se midieron de nuevo.

`git diff --check` sin hallazgos. No hay lint backend. Sin navegador.

## Limitaciones y riesgos

* No se toco el servidor ni se probo con Let's Encrypt real.
* curl de esta maquina negocio HTTP/1.1 con TLS: HTTP/2 no quedo probado. Las cabeceras no dependen de
  la version.
* El servidor puede tener otra version de `caddy:2`, porque es una etiqueta mayor.
* El reemplazo con borrado y copia deja una ventana en que el sitio responde mal y no conserva copia
  para volver atras; el cambio de carpeta de la seccion 7 evita las dos cosas.
* Una pestana abierta con un index.html viejo que pida un chunk ya borrado recibe 404. Antes recibia
  index.html, que tampoco cargaba como JavaScript.
