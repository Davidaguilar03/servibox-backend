# AGENTS.md: reglas de trabajo en ServiBox

Este archivo es identico en los dos repos (servibox-backend y servibox-frontend). Si lo cambias en uno, cambialo igual en el otro. Aplica a cualquier agente (Claude Code, Codex u otro).

## Proyecto
ServiBox es un SaaS multi-tenant para servitecas (llantas, lubricantes, baterias y servicios automotrices) en Colombia. Dos repos: servibox-backend (Spring Boot, Java, PostgreSQL 16 en produccion, H2 en perfil dev, migraciones Flyway) y servibox-frontend (React, Vite, TypeScript, Tailwind v4, TanStack Query, Vitest, ESLint). Entorno: Windows con PowerShell. Varios agentes pueden trabajar en los mismos repos, pero de a uno a la vez.

## Antes de tocar nada
1. Lee todos los archivos de docs/ai-context/ en orden numerico y la nota mas reciente de docs/ai-context/sessions/. En el backend esa carpeta esta dentro de servibox-backend/ (usa git rev-parse --show-toplevel para ubicar la raiz del repo).
2. No asumas: si un dato del prompt contradice el codigo, detente y reportalo.
3. Cada tarea llega como un prompt con las secciones GIT, LECTURA PREVIA, IMPLEMENTACION, PRUEBAS, DOCS, VERIFICACION y REPORTA AL FINAL (el cierre llega despues como otro prompt). Sigue el orden y no te saltes pasos.
4. Cada repo se modifica solo desde sus propias tareas. Leer el otro repo esta permitido solo si el prompt lo pide.

## Reglas de trabajo
- Una sola tarea a la vez. No lances procesos pesados en paralelo (la maquina tiene pocos recursos). Al terminar, deten backend, Vite y Edge y borra carpetas temporales.
- Idioma: respuestas y docs en español; docs/ai-context sin tildes; texto de interfaz con tildes; codigo, identificadores, ramas y commits en ingles.
- Estilo: nunca uses la raya larga ni el doble guion en prosa. Sin emojis en codigo. Respuestas concisas, sin elogios. Abre el reporte diciendo si se puede continuar o que hay que corregir, y pon al inicio cualquier advertencia que cambie una decision (el usuario no lee las notas del final).
- Cambios minimos. No agregues dependencias, abstracciones ni funciones que la tarea no pida. No instales navegadores.
- Pruebas: reporta el total (base mas nuevas) y lista las pruebas existentes que ajustaste y por que. Si el prompt dice que no corras la suite base, no la corras.
- Pruebas en navegador: Edge en modo sin ventana controlado con scripts guardados en una carpeta temporal fuera del repo (el scratchpad, por ejemplo %TEMP%\servibox-scratch). Nunca los agregues al repo.
- Verificacion numerica: compara cifras de pantalla con la API y con los archivos exportados. Reporta esperado frente a obtenido, sin ocultar diferencias.
- Si no puedes hacer un paso (Docker apagado, herramienta ausente), dilo y no lo sustituyas por otra via.

## Git
- La rama llega en el prompt (en ingles, tipo/NN-nombre). Antes de crearla: arbol limpio, main al dia con origin/main y el PR anterior mergeado.
- No hagas commit hasta que el prompt de cierre lo pida. En el cierre: pruebas, build y lint; agrega solo los archivos de la tarea (en el backend, excluye siempre .idea); commit con mensaje igual al nombre de la rama; sin Co-Authored-By ni mencion de ningun agente ni lineas tipo "Generated with"; git push -u origin con la rama; gh pr create con base main.
- Nunca hagas merge, push forzado ni cambies de rama al terminar. El merge, el checkout a main y el pull los hace David.

## Documentacion por tarea
Entrada nueva arriba en 03-DECISIONS (marca como reemplazado lo que contradiga), actualiza los demas docs si aplica y crea docs/ai-context/sessions/AAAA-MM-DD-nombre.md con cifras y limitaciones. Si el backend agrega un mensaje de error nuevo, listalo con su texto exacto en la nota de sesion para que el frontend lo agregue a su mapa (src/api/errorMessages.ts).

## Reglas de dominio (el detalle esta en docs/ai-context)
- El costo de lo vendido se congela por linea de venta; utilidad bruta = ventas sin IVA menos costo de lo vendido; Compras no es parte del estado de resultados.
- Precio sugerido e IVA del costo en pesos enteros, mitad hacia arriba, una sola vez al final.
- NIT y telefono obligatorios en el registro y en PUT /api/tenant.
- Toda consulta filtra por tenant. Nunca se commitean secretos.
- Frontend: el sistema de diseno (05-DESIGN-SYSTEM.md) lo protege designSystem.test.ts; no cambies colores ni uses clases fuera del sistema. Los textos salen del glosario (src/copy/glossary.ts y 06-COPY.md); todo error pasa por reportError y el mapa de mensajes.
- Backend: arranque dev con .\mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" desde la carpeta del backend; si el 8080 esta ocupado usa otro puerto y ajusta el proxy de Vite solo para la prueba, sin dejarlo cambiado.
