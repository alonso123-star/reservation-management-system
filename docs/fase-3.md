# Fase 3 — Catálogo: informe de entrega

Fecha de verificación: 9 de septiembre de 2026. Base: `33f35405d629b267365c162e38e715feaee4ca21`. Implementación local autorizada exclusivamente para catálogo; pendiente de revisión del usuario. Sin staging, commit, push ni tags.

## Alcance implementado

RoomType y Room, catálogo público, inventario ADMIN/EMPLEADO, creación y edición administrativa, activación/desactivación, estado operativo, filtros y paginación en PostgreSQL. Incluye API REST, UI React, validación, autorización efectiva en backend, DTOs, auditoría, concurrencia de edición, Flyway y Swagger.

No se implementaron disponibilidad por fechas, reservas, idempotencia de reservas, solapamientos, daterange, anti-overbooking, pagos, check-in, check-out ni funcionalidades de Fases 4/5 o posteriores. El catálogo funciona independientemente de esas fases. V1 ya contenía btree_gist antes de esta tarea; no se modificó ni se utiliza en V3.

## Modelo y migración

La última migración existente y aplicada antes de comenzar era V2. `backend/src/main/resources/db/migration/V3__catalog.sql` crea exclusivamente dos tablas, sus restricciones e índices. V1 y V2 permanecen intactas.

| Entidad | Campos finales |
|---|---|
| RoomType | UUID id; name único sin distinguir mayúsculas; description; capacity; BigDecimal basePrice; active; version; createdAt; updatedAt |
| Room | UUID id; relación ManyToOne con RoomType; code único normalizado en mayúsculas; floor; operationalStatus; active; version; createdAt; updatedAt |

La relación es un tipo a muchas habitaciones. La FK impide referencias inexistentes y no tiene cascada de borrado. Se preservan los recursos mediante desactivación. PostgreSQL exige NOT NULL, PK, FK, UNIQUE y CHECK. Precio NUMERIC(12,2) positivo; capacidad 1–1000; piso -5–200. Estados `ACTIVE`, `MAINTENANCE`, `OUT_OF_SERVICE`; nunca representan disponibilidad para fechas.

Índices: `uq_room_types_name`, `idx_room_types_catalog`, `uq_rooms_code`, `idx_rooms_type`, `idx_rooms_catalog`, además de las PK. Hibernate valida el esquema; no lo modifica.

## API, filtros y permisos

Contrato completo, requests, responses y errores en [catalog-api.md](catalog-api.md). Las 13 operaciones nuevas usan `/api/v1`:

| Operaciones | Público/CLIENTE | EMPLEADO | ADMIN |
|---|---|---|---|
| GET `/room-types`, `/room-types/{id}`, `/rooms`, `/rooms/{id}` | Sí, visibles | Sí, visibles | Sí, visibles |
| GET `/staff/room-types`, `/staff/room-types/{id}`, `/staff/rooms`, `/staff/rooms/{id}` | No | Sí | Sí |
| POST `/room-types`, PATCH `/room-types/{id}` | No | No | Sí |
| POST `/rooms`, PATCH `/rooms/{id}` | No | No | Sí |
| PATCH `/rooms/{id}/status` | No | Sí | Sí |

Tipos públicos: activos. Habitaciones públicas: activas, operativas y con tipo activo. Las consultas directas a elementos ocultos devuelven 404. El DTO público omite actividad, versiones y timestamps internos; no se exponen entidades JPA.

Tipos filtran por nombre (`q`), actividad y límites de capacidad; habitaciones por código (`q`), tipo, piso, estado y actividad. `page` desde 0, `size` 1–100 (20 por defecto), `sort` con lista de campos permitidos y UUID como desempate. Se ejecutan en PostgreSQL mediante Specifications/Pageable; el selector de tipos también pagina en el servidor.

PATCH exige la versión leída; campos omitidos o null no cambian. `@Version` protege ambos recursos ante transacciones que leyeron la misma versión. Las restricciones únicas resuelven duplicados en la base. Errores usan Problem Details y códigos `TYPE_NAME_TAKEN`, `ROOM_CODE_TAKEN`, `STALE_VERSION`; la interfaz permite recargar sin sobrescribir automáticamente.

Se conserva la autenticación de Fase 2 y su validación de roles/sesiones. Se precisó una descripción anterior demasiado amplia de CSRF: la configuración existente de Resource Server exceptúa Bearer explícito; CSRF protege operaciones basadas en cookies y Origin se valida en todas las mutaciones. React sigue enviando CSRF también con JWT. Esto es una aclaración de la política existente, no un cambio del flujo de autenticación. Referencia técnica y decisiones en [ADR 0003](adr/0003-catalogo.md).

AuditService registra creaciones, ediciones, activación/desactivación y cambios de estado en la misma transacción. Incluye actor, acción, recurso, id y requestId, sin cuerpos sensibles. Un rollback no deja auditoría de éxito. No se creó pantalla ni infraestructura paralela de auditoría.

## Frontend

- `/catalog/types` y `/catalog/rooms`: consulta pública, filtros, ordenación, paginación y enlaces al detalle público.
- `/catalog/types/:id` y `/catalog/rooms/:id`: detalle público o error 404.
- `/staff/catalog/types` y `/staff/catalog/rooms`: inventario protegido, formularios ADMIN y cambio de estado EMPLEADO/ADMIN.
- RoomType: crear/editar nombre, descripción, capacidad, tarifa y actividad.
- Room: crear/editar código, tipo, piso, estado y actividad. Selector de tipos con búsqueda y páginas.
- Estados de carga, vacío, éxito, error, botones pendientes y recuperación ante 409; errores 401/403/404 reutilizan el cliente HTTP y la autenticación.
- React Hook Form/Zod, TanStack Query, estilos existentes y rutas reutilizadas. Precio enviado como texto decimal; sin cálculos monetarios con números binarios.

En navegador real se comprobó el catálogo público vacío y la redirección del inventario anónimo a login. Los formularios y acciones ADMIN/EMPLEADO se comprobaron en pruebas de componentes y HTTP con PostgreSQL efímero; no se afirma un recorrido E2E administrativo manual ni se insertaron usuarios privilegiados en la base de desarrollo.

## Archivos principales

Nuevos archivos:

| Grupo | Archivos |
|---|---|
| Dominio backend | `rooms/domain/RoomType.java`, `rooms/domain/Room.java` |
| Persistencia | `rooms/infrastructure/RoomTypeRepository.java`, `rooms/infrastructure/RoomRepository.java` |
| Aplicación | `rooms/application/CatalogService.java` |
| API | `rooms/api/CatalogController.java`, `CatalogRequests.java`, `CatalogViews.java`, `CatalogFilters.java`; `shared/api/PageView.java` |
| Esquema y pruebas | `backend/src/main/resources/db/migration/V3__catalog.sql`; `backend/src/test/java/com/portfolio/reservation/CatalogIT.java` |
| Interfaz | `frontend/src/features/rooms/CatalogPage.tsx`, `CatalogForms.tsx`, `TypePicker.tsx`, `RoleGuard.tsx`, `api.ts`, `catalog.css`, `Catalog.test.tsx` |
| Documentación | `docs/catalog-api.md`, `docs/adr/0003-catalogo.md`, este informe |

Las rutas Java abreviadas de la tabla parten de `backend/src/main/java/com/portfolio/reservation/`.

Archivos existentes modificados: `SecurityConfiguration.java` (lecturas públicas y comentario aclaratorio), `ApiErrors.java` (409 específicos), `SystemController.java` (fase 3), `OpenApiConfiguration.java`, `FoundationIT.java`, `frontend/src/app/App.tsx`, `frontend/src/features/auth/api/auth.ts` (GET público reutilizable), `frontend/src/main.tsx`, `scripts/smoke.mjs`, `README.md`, `docs/plan-inicial.md`. Se precisaron párrafos CSRF de `docs/auth-api.md`, `docs/adr/0002-identidad-autenticacion.md` y `docs/fase-2.md`.

AuthService, la implementación de sesiones/JWT/refresh, las migraciones históricas, los manifiestos/lockfiles de dependencias y Compose no tienen modificaciones.

## Verificación

| Comprobación | Resultado observado |
|---|---|
| `docker compose --profile test run --rm backend-tests` | BUILD SUCCESS; 39 tests, 0 fallos, 0 errores, 0 omitidos |
| AuthenticationIT | 17/17 pasan; suite de identidad intacta |
| FoundationIT | 4/4 pasan; actualizada la expectativa de fase/esquema |
| CatalogIT | 18/18 pasan; API, validaciones, visibilidad, roles, PostgreSQL, auditoría y concurrencia real |
| `npm.cmd test -- --pool=threads --maxWorkers=1` | 35/35 pasan en 5 archivos; 20 pruebas nuevas de catálogo y 15 existentes |
| `npm.cmd run build` | TypeScript y Vite correctos; bundle JS 403.96 kB (123.98 kB gzip) |
| `npm.cmd run lint` | Correcto, sin errores ni advertencias |
| Build backend en Docker | `./mvnw -B -ntp -DskipTests package`: BUILD SUCCESS; las pruebas se ejecutaron por separado mediante verify |
| Build frontend en Docker | TypeScript/Vite correctos, misma salida que el build local |
| `docker compose up --build --wait --wait-timeout 240` | Exit 0; db, backend y frontend healthy |
| PostgreSQL/Flyway desde cero | V1→V2→V3 correctas en PostgreSQL 18.6 de Testcontainers |
| PostgreSQL/Flyway existente | Logs confirman versión inicial 2, aplicación de una migración, versión final 3; V1/V2/V3 success=true |
| Smoke HTTP final | Correcto: assets, proxy, readiness, catálogo, inventario protegido, Swagger y esquemas OpenAPI 200/201/409 |
| Repetición final de CatalogIT | `docker compose --profile test run --rm backend-tests '-Dit.test=CatalogIT'`: BUILD SUCCESS; 18 tests, 0 fallos, 0 errores, 0 omitidos, con aserciones nuevas del contrato OpenAPI |

La verificación final confirmó esquemas JSON de éxito para las lecturas y creaciones, y ProblemDetail para 409. La reconstrucción final de Compose terminó con exit 0 y los tres servicios healthy. No hubo cambios de código posteriores a estas comprobaciones.

Los dos fallos iniciales de la suite nueva se resolvieron ajustando fixtures/aserciones: el conteo de auditoría incluía el login y la prueba CSRF asumía erróneamente que Bearer no estaba exceptuado. El timeout inicial de workers de Vitest se resolvió con threads y un worker, sin saltar pruebas. Se corrigió un error de tipado de React en la visualización de errores y las advertencias de lint. La inspección final del contrato detectó respuestas exitosas faltantes en OpenAPI; se añadieron anotaciones explícitas y aserciones sobre los esquemas 200/201/409.

No se ejecutó CI remota. Los warnings de APIs obsoletas usados por tests y conexiones de Testcontainers que se cierran al terminar una clase no causaron fallos de verify.

## Seguridad, límites y decisiones para revisión

- Sin hallazgos de secretos reales en los cambios: revisión de patrones de credenciales/claves/tokens y comparación con los valores locales sensibles, sin imprimirlos. Las credenciales constantes de CatalogIT son fixtures exclusivos de su base efímera.
- `.env` real, `.tools`, node_modules, dist, target y otros artefactos permanecen ignorados y fuera del conjunto Git. No se añadieron dependencias.
- La base de desarrollo mantiene el catálogo vacío; no se añadieron cuentas ni datos de demostración. Para usar el inventario se necesitan usuarios ADMIN/EMPLEADO provisionados de forma controlada; no hay administrador automático ni endpoint de escalada. Gestión de roles permanece en su fase prevista.
- Revisar los límites explícitos de capacidad (1–1000), piso (-5–200), moneda PEN y visibilidad pública descritos arriba antes de aprobar la entrega. No bloquean las comprobaciones realizadas.
- La paginación por offset puede desplazar resultados si otros usuarios crean o desactivan registros entre peticiones. Las búsquedas de subcadena no usan índices especializados; evaluar el volumen real antes de optimizar.
- En caso de rechazar esta entrega, revertir solo código no revierte la V3 ya aplicada al volumen local. Cualquier cambio de esquema posterior deberá respetar el historial Flyway; no se ha borrado ni recreado el volumen de desarrollo.

## Git final

- Rama: `main`; upstream: `origin/main`.
- HEAD y origin/main: `33f35405d629b267365c162e38e715feaee4ca21`; diferencia `0 / 0` commits.
- Working tree: 14 archivos existentes modificados y 22 nuevos, 36 en total, todos para revisión.
- Staging vacío; `git diff --cached --quiet` devuelve 0. `git diff --check` no encuentra errores de espacios.
- Revisión final de los 36 archivos sin hallazgos de secretos ni artefactos generados; `.env` real sigue ignorado y no rastreado.
- No se realizó staging, commit, push ni creación de tags. No se alteró el historial ni origin/main. Fase 4 no iniciada.

Entrega detenida a la espera de revisión del usuario.
