# Reglas para agentes de IA en este repositorio

Estas reglas aplican a cualquier agente de IA que lea o modifique codigo de ServiBox.

## 1. Caracteres prohibidos

Nunca usar el caracter guion largo (em dash) ni el doble guion en codigo, comentarios,
mensajes de commit ni documentacion de esta carpeta. Usar coma, dos puntos o punto y coma.

## 2. Leer antes de escribir

Antes de tocar codigo, leer los archivos relevantes de `docs/ai-context/` segun el modulo
que se vaya a modificar:

* Cambios de arquitectura o infraestructura: `01-ARCHITECTURE.md`
* Nombres, estilo, errores, DTOs o terminologia: `02-CONVENTIONS.md`
* Motivo de una decision existente: `03-DECISIONS.md`

## 3. Actualizar el contexto en el mismo commit

Despues de cualquier cambio significativo (nueva entidad, decision de arquitectura,
convencion nueva) se actualiza el archivo correspondiente de esta carpeta en el mismo
commit que el codigo. Un commit que cambia arquitectura sin actualizar el contexto
esta incompleto.

## 4. No duplicar contexto

Cada archivo tiene un tema unico. Si algo ya esta documentado en otro archivo, se enlaza
en vez de repetirlo. Ejemplo: `[ver estrategia multi-tenant](01-ARCHITECTURE.md)`.

## 5. Consistencia de idioma

Esta carpeta usa espanol o ingles segun convenga al tema, pero cada archivo debe mantener
un solo idioma de principio a fin. No mezclar dentro de un mismo archivo.

## 6. Bitacora de sesiones

Las sesiones de trabajo relevantes se registran en `sessions/` con el formato
`AAAA-MM-DD-descripcion.md`.
