# Despliegue de ServiBox en un VPS

Un solo servidor Linux con Docker Compose: PostgreSQL 16, el backend y Caddy (HTTPS
automático con Let's Encrypt). El frontend se sirve como archivos estáticos desde Caddy.

* `https://servibox.co`: frontend (carpeta `dist` del build de servibox-frontend).
* `https://api.servibox.co`: API del backend.

Archivos (en la raíz del repo servibox-backend):

| Archivo | Para qué |
|-|-|
| `Dockerfile` | Imagen del backend: compila con JDK 21 y corre con JRE 21, usuario 10001 sin privilegios, perfil prod |
| `deploy/docker-compose.prod.yml` | Servicios `postgres`, `backend` y `caddy`. Solo Caddy publica puertos (80 y 443) |
| `deploy/Caddyfile` | HTTPS, proxy hacia el backend con `X-Real-IP`, HSTS y frontend con SPA fallback |
| `deploy/.env.example` | Plantilla de variables, sin secretos |
| `deploy/backup.sh` / `deploy/restore.sh` | Respaldo diario y restauración |

Las variables del backend que no aparecen aquí (plazos, límites, outbox) tienen valores por
defecto razonables y están descritas en [DEPLOYMENT.md](../DEPLOYMENT.md).

## 1. Requisitos del servidor

* Ubuntu 24.04 LTS, 2 GB de RAM, 1 o 2 vCPU, 25 GB de disco o más, IPv4 pública.
* Memoria: el compose limita postgres a 512 MB, backend a 900 MB (heap de la JVM al 75%) y
  Caddy a 128 MB. Agregar 2 GB de swap como colchón (abajo).
* Acceso al panel DNS de Porkbun para servibox.co.

## 2. Preparación del servidor

Como root, la primera vez (reemplazar `david` si se usa otro nombre):

```bash
apt update && apt -y upgrade
adduser david
usermod -aG sudo david
# Copiar la llave pública SSH de root al usuario nuevo (si el proveedor la cargó en root)
rsync --archive --chown=david:david ~/.ssh /home/david
```

Llaves SSH. Si aún no hay una llave en el PC (PowerShell):

```powershell
ssh-keygen -t ed25519 -C "servibox-vps"
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub   # pegarla en el panel del proveedor o en /home/david/.ssh/authorized_keys
ssh david@IP_DEL_SERVIDOR
```

Con el acceso por llave comprobado en una segunda terminal, desactivar contraseña y root por
SSH:

```bash
sudo sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/; s/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
```

Cortafuegos (solo 22, 80 y 443):

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

Docker publica sus puertos por fuera de ufw. Por eso el compose solo publica 80 y 443 (Caddy);
PostgreSQL y el backend no tienen puerto publicado y no son alcanzables desde internet.

Swap de 2 GB:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Docker (repositorio oficial):

```bash
sudo apt -y install ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker david
# Cerrar la sesión SSH y volver a entrar para que el grupo docker aplique
docker compose version
```

## 3. DNS en Porkbun

En Porkbun, servibox.co, "DNS": Porkbun crea por defecto registros de parking (un `ALIAS` o
`A` en la raíz y un `CNAME` de `*` o `www` hacia `pixie.porkbun.com` o `uixie.porkbun.com`).

1. Borrar el registro de la raíz que apunta al parking (tipo `ALIAS`/`CNAME` hacia
   `pixie.porkbun.com`, o `A` con una IP de Porkbun) y el comodín `*` si existe. No puede
   haber un `ALIAS`/`CNAME` y un `A` en el mismo nombre.
2. Crear:

| Tipo | Host | Respuesta | TTL |
|-|-|-|-|
| A | (vacío, la raíz) | IP_DEL_SERVIDOR | 600 |
| A | api | IP_DEL_SERVIDOR | 600 |

3. No crear registros `AAAA` salvo que el servidor tenga IPv6 configurada y abierta: Let's
   Encrypt la usaría y fallaría.
4. Comprobar antes de arrancar Caddy (desde el PC):

```powershell
Resolve-DnsName servibox.co -Type A
Resolve-DnsName api.servibox.co -Type A
```

Ambos deben dar la IP del servidor. Si Caddy arranca antes de que el DNS propague, reintenta
solo; pero muchos intentos fallidos pueden topar el límite de Let's Encrypt.

## 4. Variables

`deploy/.env` (copia de `.env.example`). El compose arma `DB_URL`, `DB_USERNAME` y
`DB_PASSWORD` desde `POSTGRES_*` y fija `PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER=X-Real-IP`.

| Nombre | Obligatoria | Ejemplo | Notas |
|-|-|-|-|
| POSTGRES_DB | Sí | `servibox` | Solo minúsculas, números y `_`. Sin ella el compose no arranca |
| POSTGRES_USER | Sí | `servibox` | Dueño de la base; Flyway migra con él |
| POSTGRES_PASSWORD | Sí | 64 caracteres hex | Se fija al crear el volumen la primera vez; cambiarla después en .env no cambia la clave real |
| JWT_SECRET | Sí | 64 caracteres hex | Firma los JWT de las servitecas. Falta, corta (menos de 32 bytes) o la de desarrollo: no arranca. Cambiarla cierra todas las sesiones |
| PLATFORM_JWT_SECRET | Sí | 64 caracteres hex | Firma los JWT de plataforma; distinta de JWT_SECRET. Falta o repetida: no arranca |
| MAIL_OUTBOX_ENCRYPTION_KEY | Sí | 64 caracteres hex | Cifra los correos pendientes; distinta de las dos JWT. Falta o repetida: no arranca. Rotación en DEPLOYMENT.md |
| ACTIVATION_BASE_URL | Sí | `https://servibox.co` | Base del enlace de activación. Falta o sin https: no arranca |
| CORS_ALLOWED_ORIGINS | Sí con este esquema | `https://servibox.co` | Orígenes exactos separados por coma. Falta: la app arranca pero el navegador bloquea toda llamada del frontend a la API. Con `*`, `http://` o una ruta: no arranca |
| REGISTRATION_REQUESTS_ENABLED | No (default false) | `false` | false: `/api/registration-requests` y `/activate` dan 404 (registro cerrado). Abrir solo tras verificar un correo real |
| SMTP_HOST | No | `smtp.proveedor.com` | Sin él los correos quedan PENDING y la app arranca (aviso en el log) |
| SMTP_PORT | No (587) | `587` | 465 con `SMTP_SECURITY=ssl` |
| SMTP_SECURITY | No (starttls) | `starttls` | `starttls` o `ssl`; otro valor con SMTP_HOST: no arranca |
| SMTP_USERNAME / SMTP_PASSWORD | No | | Credenciales del proveedor; vacío desactiva la autenticación |
| MAIL_FROM | Sí con SMTP_HOST | `ServiBox <no-reply@servibox.co>` | Con SMTP_HOST y sin MAIL_FROM: no arranca |
| API_DOMAIN / FRONTEND_DOMAIN | No | `api.servibox.co` / `servibox.co` | Dominios de Caddy |
| BACKEND_TAG | No (latest) | `a78e0e3` | Etiqueta de la imagen; ver sección 9 |
| PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER | Fijada en el compose | `X-Real-IP` | No ponerla en .env. Vacía: todos los visitantes compartirían la IP de Caddy y un atacante bloquearía el login de todos (WARN al arrancar) |

Generar cada secreto (64 caracteres hexadecimales; sin `$`, `#` ni comillas, que el compose
interpretaría). Una ejecución por variable; nunca reutilizar un valor.

Linux:

```bash
openssl rand -hex 32
```

PowerShell (Windows PowerShell 5.1):

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | ForEach-Object { $_.ToString('x2') })
```

Para no dejar los secretos en pantalla ni en el historial, en el servidor se pueden escribir
directo al archivo:

```bash
cd ~/servibox-backend/deploy
cp .env.example .env && chmod 600 .env
for v in POSTGRES_PASSWORD JWT_SECRET PLATFORM_JWT_SECRET MAIL_OUTBOX_ENCRYPTION_KEY; do
  sed -i "s/^$v=.*/$v=$(openssl rand -hex 32)/" .env
done
nano .env   # revisar dominios, ACTIVATION_BASE_URL, CORS_ALLOWED_ORIGINS y SMTP
```

Guardar una copia de `.env` en un gestor de contraseñas: sin MAIL_OUTBOX_ENCRYPTION_KEY y
POSTGRES_PASSWORD no se pueden usar los respaldos ni lo pendiente del outbox.

## 5. Primer despliegue (orden exacto)

1. Subir el código al servidor (como `david`):

   ```bash
   cd ~
   git clone https://github.com/Davidaguilar03/servibox-backend.git
   cd servibox-backend
   git checkout main
   chmod +x deploy/*.sh
   ```

   Si el repo es privado, usar una deploy key de solo lectura o subir el repo con
   `scp -r` desde el PC.

2. Crear `deploy/.env` (sección 4) y la carpeta del frontend: `mkdir -p deploy/frontend`.
3. Subir el frontend (sección 7). Puede hacerse después; mientras tanto servibox.co responde
   vacío.
4. Construir la imagen con la etiqueta del commit y arrancar:

   ```bash
   cd ~/servibox-backend/deploy
   sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=$(git rev-parse --short HEAD)/" .env
   docker compose -f docker-compose.prod.yml build backend
   docker compose -f docker-compose.prod.yml up -d
   docker compose -f docker-compose.prod.yml ps
   ```

   La compilación tarda varios minutos en un servidor de 2 GB (la swap ayuda).
5. Revisar el arranque del backend:

   ```bash
   docker compose -f docker-compose.prod.yml logs backend | grep -E "Successfully applied|Current version|Started ServiboxApplication|WARN|ERROR"
   ```

   Debe aparecer Flyway en la versión 13 (`Successfully applied 13 migrations` la primera vez),
   `Started ServiboxApplication`, y ningún WARN de `PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER`. El
   único WARN esperado sin SMTP es el del outbox sin transporte.
6. Certificados:

   ```bash
   docker compose -f docker-compose.prod.yml logs caddy | grep -iE "certificate obtained|error"
   curl -sSI https://api.servibox.co/api/health
   ```

   Debe decir `certificate obtained successfully` para ambos dominios y la petición devolver
   `HTTP/2 200`.
7. Crear el administrador de plataforma (sección 6) y hacer la verificación (sección 11).

## 6. Administrador de plataforma

Una sola vez, con el stack arriba. Corre un contenedor temporal con la misma imagen y
variables, sin servidor web, que pide correo y contraseña con entrada oculta:

```bash
cd ~/servibox-backend/deploy
docker compose -f docker-compose.prod.yml run --rm --no-deps backend --platform-bootstrap
```

* La contraseña no se escribe en el comando: no queda en el historial de bash. `--rm` borra
  el contenedor y su salida al terminar; el bootstrap solo imprime textos fijos.
* Contraseña de 12 a 72 bytes. Guardarla en el gestor de contraseñas.
* Si ya existe un administrador, rechaza y sale con código 1 sin cambiar nada.
* Si responde que no hay consola, falta la TTY: no agregar `-T` y ejecutarlo desde una
  sesión SSH normal (no desde un script ni cron).

## 7. Subir el frontend

En el PC, desde servibox-frontend (con la URL de la API de producción que use ese repo):

```powershell
npm ci
npm run build
scp -r dist/* david@IP_DEL_SERVIDOR:~/servibox-backend/deploy/frontend/
```

Caddy lee la carpeta al vuelo; no hace falta reiniciar. Para reemplazar una versión sin
dejar archivos viejos, subir a una carpeta nueva y cambiarla de un golpe:

```bash
# en el servidor, tras subir a ~/frontend-nuevo con scp
cd ~/servibox-backend/deploy
rm -rf frontend-anterior && mv frontend frontend-anterior && mv ~/frontend-nuevo frontend
```

Volver atrás: `rm -rf frontend && mv frontend-anterior frontend`. Caddy monta la carpeta
`deploy/frontend`; si se reemplaza la carpeta (no su contenido), reiniciar Caddy:
`docker compose -f docker-compose.prod.yml restart caddy`.

## 8. Respaldos

`deploy/backup.sh` guarda `deploy/backups/servibox-AAAAMMDD-HHMMSS.dump` (pg_dump formato
custom, comprimido) y borra los de más de 14 días. Agendarlo a las 3:30 a. m. hora del
servidor:

```bash
crontab -e
# agregar la línea:
30 3 * * * /home/david/servibox-backend/deploy/backup.sh >> /home/david/servibox-backup.log 2>&1
```

Comprobar al día siguiente: `tail -n 3 ~/servibox-backup.log` y `ls -lh ~/servibox-backend/deploy/backups`.

Los respaldos quedan en el mismo servidor: si el disco o el VPS se pierden, se pierden con él.
Copiarlos fuera con regularidad, por ejemplo desde el PC:

```powershell
scp david@IP_DEL_SERVIDOR:~/servibox-backend/deploy/backups/servibox-*.dump C:\ruta\respaldos\
```

Restaurar (`deploy/restore.sh`):

* Comprobar un respaldo sin tocar producción, en una base aparte:

  ```bash
  ./restore.sh backups/servibox-AAAAMMDD-HHMMSS.dump servibox_verif
  docker compose -f docker-compose.prod.yml exec postgres sh -c 'psql -U "$POSTGRES_USER" -d servibox_verif -c "select max(version) from flyway_schema_history; select count(*) from tenants;"'
  docker compose -f docker-compose.prod.yml exec postgres sh -c 'dropdb -U "$POSTGRES_USER" servibox_verif'
  ```

* Restaurar producción: `./restore.sh backups/servibox-AAAAMMDD-HHMMSS.dump`. Pide escribir
  `RESTAURAR servibox`, detiene el backend, borra y recrea la base, restaura y arranca el
  backend. Se pierde todo lo posterior al respaldo. Sacar antes un respaldo del estado
  actual con `./backup.sh`.

## 9. Actualizar a una versión nueva y volver atrás

Actualizar (después de que David mergee el PR en main):

```bash
cd ~/servibox-backend
./deploy/backup.sh
grep '^BACKEND_TAG=' deploy/.env      # anotar la etiqueta actual para volver atrás
git pull --ff-only
cd deploy
sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=$(git rev-parse --short HEAD)/" .env
docker compose -f docker-compose.prod.yml build backend
docker compose -f docker-compose.prod.yml up -d backend
docker compose -f docker-compose.prod.yml logs -f backend   # esperar Started, salir con Ctrl+C
```

Mientras el backend reinicia (unos 30 a 60 segundos) la API responde 502.

Volver atrás: la imagen anterior sigue en el servidor con su etiqueta.

```bash
cd ~/servibox-backend/deploy
sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=ETIQUETA_ANTERIOR/" .env
docker compose -f docker-compose.prod.yml up -d backend
```

El código en `~/servibox-backend` queda en la versión nueva; solo la imagen vuelve atrás.
Antes de la siguiente actualización, corregir en main lo que falló.

Si la versión nueva aplicó una migración Flyway, la versión anterior puede no arrancar
(Hibernate valida el esquema). En ese caso restaurar el respaldo tomado antes de actualizar
(sección 8) y luego volver a la etiqueta anterior. Se pierde lo registrado entre medias.

Limpiar imágenes viejas de vez en cuando, conservando las dos últimas:
`docker image ls servibox-backend` y `docker image rm servibox-backend:ETIQUETA`.

## 10. Operación diaria

```bash
cd ~/servibox-backend/deploy
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --since 1h backend
docker compose -f docker-compose.prod.yml restart backend
```

Los logs rotan solos (5 archivos de 10 MB por servicio). Los contenedores reinician solos tras
un reinicio del servidor (`restart: unless-stopped`). Actualizaciones de seguridad de Ubuntu:
`sudo apt update && sudo apt -y upgrade` y, si lo pide, `sudo reboot`.

## 11. Verificación posterior al despliegue

Desde el PC o el servidor (bash; en PowerShell usar `curl.exe` con las mismas opciones).
`API=https://api.servibox.co`.

1. Salud y HSTS de la API:

   ```bash
   curl -sSi https://api.servibox.co/api/health
   ```

   Esperado: `HTTP/2 200`, cuerpo `{"status":"UP"}` y una sola cabecera
   `strict-transport-security: max-age=15552000`.

2. HSTS y SPA del frontend:

   ```bash
   curl -sSI https://servibox.co/ | grep -i strict-transport-security
   curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' https://servibox.co/ruta/que/no/existe
   ```

   Esperado: `max-age=15552000` y `200 text/html...` (index.html).

3. HTTP redirige a HTTPS:

   ```bash
   curl -sSI http://api.servibox.co/api/health | head -n 3
   ```

   Esperado: `308 Permanent Redirect` hacia `https://`.

4. Login de plataforma (la clave se lee oculta y no queda en el historial):

   ```bash
   read -r -p 'Correo: ' PEMAIL; read -r -s -p 'Clave: ' PPASS; echo
   printf '{"email":"%s","password":"%s"}' "$PEMAIL" "$PPASS" | curl -sS -o /dev/null -w '%{http_code}\n' \
     -X POST https://api.servibox.co/api/platform/auth/login -H 'Content-Type: application/json' --data-binary @-
   unset PPASS
   ```

   Esperado: `200`. Con una clave incorrecta: `401`.

5. Registro cerrado (compuerta en false):

   ```bash
   curl -sS -w '\n%{http_code}\n' -X POST https://api.servibox.co/api/registration-requests -H 'Content-Type: application/json' -d '{}'
   curl -sS -w '\n%{http_code}\n' -X POST https://api.servibox.co/api/auth/register -H 'Content-Type: application/json' -d '{}'
   ```

   Esperado en ambos: `{"error":"Recurso no encontrado","status":404}` y `404`.

6. CORS solo para el frontend:

   ```bash
   curl -sS -o /dev/null -D - -X OPTIONS https://api.servibox.co/api/auth/login -H 'Origin: https://servibox.co' -H 'Access-Control-Request-Method: POST' | grep -iE '^HTTP|access-control-allow-origin'
   curl -sS -o /dev/null -D - -X OPTIONS https://api.servibox.co/api/auth/login -H 'Origin: https://otro.example' -H 'Access-Control-Request-Method: POST' | grep -iE '^HTTP|access-control-allow-origin'
   ```

   Esperado: el primero `200` con `access-control-allow-origin: https://servibox.co`; el
   segundo `403` sin `access-control-allow-origin`.

7. Límite por IP real (429) y X-Real-IP falsificado ignorado. Seis logins fallidos contra una
   cuenta inexistente, cada uno con un X-Real-IP falso distinto:

   ```bash
   for i in 1 2 3 4 5 6; do
     curl -sS -o /dev/null -w "$i %{http_code}\n" -X POST https://api.servibox.co/api/auth/login \
       -H 'Content-Type: application/json' -H "X-Real-IP: 203.0.113.$i" \
       -d '{"tenantSlug":"verificacion-inexistente","username":"nadie","password":"clave-incorrecta"}'
   done
   ```

   Esperado: `401` en los cinco primeros y `429` en el sexto. Si el sexto da `401`, el proxy
   está dejando pasar el X-Real-IP del cliente: no abrir la compuerta de registro y revisar
   el Caddyfile. Ese bloqueo solo afecta a esa cuenta inexistente desde la IP de quien prueba
   y se libera en 15 minutos.

8. El login real sigue funcionando desde otra red (por ejemplo el celular sin wifi): abrir
   `https://servibox.co`, ingresar al panel de plataforma.
9. Log de arranque sin `Using generated security password` ni credenciales:

   ```bash
   docker compose -f docker-compose.prod.yml logs backend | grep -ci "password"
   ```

   Esperado: `0`.
10. Respaldo manual y comprobación (sección 8) antes de dar acceso a clientes.

Antes de abrir el registro (`REGISTRATION_REQUESTS_ENABLED=true`): configurar SMTP, verificar
un correo real en un buzón y repetir el punto 7 con `POST /api/registration-requests/activate`
(token falso). Resto de comprobaciones en [DEPLOYMENT.md](../DEPLOYMENT.md).
