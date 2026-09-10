# Fase 5 — Reservas

Entrega local para revisión. Base aprobada: `bc824779cfdccb21015b06ccf56b54782db200ce` (Fase 4). No se prepara staging ni se autoriza commit, push o Fase 6. Este informe describe el código de esta entrega; los informes de fases anteriores conservan sus resultados históricos.

## Implementación y alcance

Una reserva corresponde a una habitación y un cliente. Se crea directamente en `CONFIRMED`, con código único, tarifa y total históricos. CLIENTE reserva para sí; EMPLEADO/ADMIN puede reservar para un CLIENTE activo. Hay historial paginado, detalle, cancelación, integración con disponibilidad, auditoría e idempotencia persistente.

No se implementan pagos, reembolsos, holds, expiración de reservas confirmadas, check-in, check-out, no-show operativo, dashboard completo, notificaciones ni administración de usuarios. Una reserva confirmada puede existir sin pago hasta Fase 6. Los estados y dos fechas operativas se preparan en el modelo, sin rutas ni botones para ejecutar esos flujos.

## Modelo y esquema

Migración nueva: `backend/src/main/resources/db/migration/V4__reservations.sql`. V1 instala `btree_gist` y se reutiliza sin cambiar su checksum. V1, V2 y V3 no se modifican. Hibernate continúa con `ddl-auto=validate`.

### `reservations`

| Columna | Tipo y regla |
|---|---|
| `id` | UUID, PK, generado por servidor |
| `code` | VARCHAR(40), NOT NULL, UNIQUE; `R-` seguido de UUID aleatorio sin guiones en mayúsculas |
| `customer_id` | UUID NOT NULL, FK users; propietario |
| `created_by` | UUID NOT NULL, FK users; actor autenticado |
| `room_id` | UUID NOT NULL, FK rooms; una habitación |
| `check_in`, `check_out` | DATE NOT NULL, entrada < salida |
| `guests` | INTEGER NOT NULL, > 0 |
| `status` | VARCHAR(20), NOT NULL, CHECK de los cinco estados |
| `agreed_nightly_rate`, `total_amount` | NUMERIC(12,2), NOT NULL, positivos |
| `currency` | VARCHAR(3), NOT NULL, tres letras mayúsculas; servidor valida Currency |
| `cancellation_reason` | VARCHAR(500), nullable mientras no esté cancelada |
| `cancelled_by`, `cancelled_at` | UUID FK users y TIMESTAMPTZ |
| `checked_in_at`, `checked_out_at` | TIMESTAMPTZ nullable, preparados sin operación implementada |
| `version` | BIGINT NOT NULL DEFAULT 0, JPA `@Version` |
| `created_at`, `updated_at` | TIMESTAMPTZ NOT NULL |

CHECK adicional: `total_amount = agreed_nightly_rate * (check_out - check_in)`. Una fila `CANCELLED` requiere actor, fecha y motivo no vacío. Las FK conservan referencias históricas; no se implementa borrado físico. Índices por cliente/entrada/id, estado/entrada/id, habitación/salida y creador; código tiene índice único y la exclusión crea GiST.

`Reservation` encapsula creación y cancelación. `ReservationView` entrega IDs, código, datos visibles de habitación, estancia, huéspedes, noches, estado, dinero histórico, datos de cancelación, fechas, versión y `canCancel`. Código/nombre de habitación se consultan al leer; el compromiso monetario queda almacenado, independiente de futuras ediciones del catálogo. No se expone la entidad JPA ni campos de seguridad del usuario.

### `idempotency_requests`

| Columna | Tipo y regla |
|---|---|
| `id` | UUID PK |
| `actor_id` | UUID NOT NULL, FK users |
| `operation` | VARCHAR(40), CHECK `CREATE_RESERVATION` / `STAFF_CREATE_RESERVATION` |
| `request_key` | UUID NOT NULL |
| `request_hash` | VARCHAR(64), NOT NULL, SHA-256 hexadecimal |
| `reservation_id` | UUID FK reservations |
| `response_json` | TEXT, snapshot de ReservationView original |
| `created_at`, `expires_at` | TIMESTAMPTZ NOT NULL; expiración > creación |

UNIQUE `(actor_id, operation, request_key)`. Reserva y respuesta deben estar ambas presentes o ambas ausentes. La fila pendiente solamente existe dentro de la transacción de creación; no se confirma antes de completar el recibo. Hay índice de expiración. No se guardan cuerpos arbitrarios, JWT, cookies ni contraseñas.

## Fechas, estados y garantía PostgreSQL

Las noches usan LocalDate y el intervalo **[entrada, salida)**. Dos estancias con salida/entrada en la misma fecha son contiguas y están permitidas. Se reutiliza `StayRules.nights` en búsqueda y creación. Fechas ausentes, iguales o invertidas y huéspedes no positivos se rechazan. La deserialización rechaza huéspedes fraccionarios; no trunca `1.5` a `1`.

| Estado | Bloquea disponibilidad y exclusión | Operación disponible |
|---|---|---|
| CONFIRMED | Sí | Creación y posterior cancelación |
| CHECKED_IN | Sí | Ninguna en Fase 5 |
| CHECKED_OUT | Sí, preserva integridad histórica | Ninguna en Fase 5 |
| CANCELLED | No | Resultado de cancelar CONFIRMED |
| NO_SHOW | No | Ninguna en Fase 5 |

Restricción final, incluida en V4:

```sql
CONSTRAINT ex_reservations_room_stay EXCLUDE USING gist (
    room_id WITH =,
    daterange(check_in, check_out, '[)') WITH &&
) WHERE (status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT'))
```

No depende de un `exists` previo ni de un bloqueo Java: PostgreSQL arbitra incluso escrituras de transacciones independientes que omiten el servicio. SQLSTATE `23P01` identifica una exclusión infringida. `ApiErrors` traduce la restricción nombrada a 409 `ROOM_NOT_AVAILABLE`, sin exponer SQL.

### Transacción y coordinación con catálogo

1. Validar estancia y reclamar recibo idempotente.
2. Validar cliente activo con rol CLIENTE.
3. Bloquear fila de habitación y después fila de su tipo (`PESSIMISTIC_WRITE`).
4. Comprobar nuevamente actividad de ambas, estado `ACTIVE`, capacidad, precio y ocupación mediante las reglas compartidas con disponibilidad.
5. Calcular precio, insertar y hacer flush para aplicar la exclusión.
6. Guardar auditoría y completar recibo antes del commit de esa misma transacción.

Una excepción revierte reserva, recibo y auditoría. Los bloqueos de fila coordinan también los cambios del catálogo; la exclusión constituye la última garantía. Las reservas de habitaciones de un mismo tipo pueden esperar al bloqueo de ese tipo: se prioriza una validación coherente de capacidad/actividad/tarifa en este monolito. No hay mutex en memoria ni dependencia de una única instancia.

Editar habitación bloquea su fila; editar tipo bloquea solamente el tipo y consulta reservas sin bloquear habitaciones. Se rechazan cambios de habitación incompatibles con reservas CONFIRMED/CHECKED_IN de salida futura, y desactivación/reducción de capacidad incompatible del tipo, con 409 `ROOM_HAS_RESERVATIONS`. El orden evita invertir habitación/tipo. Cambiar precio está permitido y conserva importes históricos. La cancelación usa control optimista de versión; dos cancelaciones concurrentes producen una sola transición y una sola auditoría.

### Evidencia de concurrencia

`ReservationIT` abre dos conexiones JDBC independientes, ambas con transacción manual. La primera inserta una reserva sin confirmar. La segunda intenta insertar un solapamiento sin utilizar los locks del servicio. Se verifica en `pg_stat_activity` que la segunda está esperando un bloqueo, se confirma la primera y se comprueba que la segunda falla con `23P01`. La consulta final exige **exactamente una reserva CONFIRMED**.

Además se prueban dos POST simultáneos con claves distintas (201 y 409, una reserva), dos con la misma clave (dos respuestas 201 con el mismo ID, una reserva/recibo/auditoría), cancelaciones simultáneas y rollback ante fallo de auditoría. Los casos incluyen solapamiento parcial, contención en ambos sentidos e igualdad; contigüidad, otra habitación y estados no bloqueantes quedan permitidos.

## Idempotencia y precio

`Idempotency-Key` UUID es obligatorio en ambas creaciones. El ámbito es actor autenticado + operación + clave. El hash SHA-256 usa cliente, habitación, fechas y huéspedes normalizados. Misma clave y contenido devuelve el snapshot original con 201 y Location; distinto contenido devuelve 409 `IDEMPOTENCY_KEY_REUSED`.

El `INSERT ... ON CONFLICT DO NOTHING` espera la transacción competidora. La posterior lectura `FOR UPDATE` observa el recibo confirmado; si la competidora revierte, se puede adquirir la clave y crear. No se confirman recibos incompletos. Un fallo de negocio deja la clave disponible para reintentar porque revierte toda la transacción.

El recibo se reproduce durante **24 horas**. Una clave vencida devuelve 409 `IDEMPOTENCY_KEY_EXPIRED`; sus filas se conservan para impedir reutilización silenciosa. No se añade un proceso de purga en esta fase. El snapshot sigue siendo la respuesta original aunque después se cancele la reserva: GET detalle devuelve el estado actual.

La tarifa proviene del tipo bloqueado, la moneda de `hotel.currency` (PEN por defecto) y las noches de la diferencia de fechas. `BigDecimal`: tarifa × noches; NUMERIC(12,2) en ambas columnas. Se rechaza un total superior a 9 999 999 999,99 con 400 `INVALID_AMOUNT`. El cliente no puede enviar tarifa, total, moneda, estado, código, creador ni propietario al endpoint propio. Una prueba modifica la tarifa del catálogo y comprueba que la reserva conserva el valor anterior.

## Contrato REST y permisos

Prefijo `/api/v1`. Se conserva JWT, validación de sesión, Origin obligatorio para mutaciones y política CSRF de Fase 2. Refresh no sustituye un access token Bearer. No se crean roles nuevos ni rutas para promover usuarios.

| Endpoint | CLIENTE | EMPLEADO / ADMIN | Éxito |
|---|---|---|---|
| POST `/reservations` | Crear propia | 403 | 201 + Location |
| POST `/staff/reservations` | 403 | Crear para CLIENTE activo | 201 + Location |
| GET `/reservations` | Solo propias | Todas, filtros de cliente/habitación | 200 |
| GET `/reservations/{id}` | Solo propia, ajena 404 | Cualquier reserva | 200 |
| POST `/reservations/{id}/cancel` | Propia y antes del día de entrada | CONFIRMED antes del check-in operativo | 200 |
| GET `/staff/customers` | 403 | Directorio mínimo de clientes activos | 200 |
| GET `/rooms/availability` | Público | Público | 200 |

Sin autenticación, las rutas privadas devuelven 401. Un cuerpo inválido puede producir 400 antes de evaluar autorización de método; esto no realiza una escritura ni concede acceso. Las pruebas de permisos envían DTO válido para comprobar 403.

Creación propia:

```json
{ "roomId": "UUID", "checkIn": "2027-06-10", "checkOut": "2027-06-12", "guests": 2 }
```

El UUID/ID de `Reservation` es generado por el servidor. `createdBy` es el identificador del actor y se obtiene del usuario autenticado mediante JWT. Para una reserva propia, el cliente propietario (`customerId`) también se determina a partir del usuario autenticado; no se acepta un `customerId` enviado por el cliente. La creación staff añade `customerId` para seleccionar al cliente propietario, mientras que `createdBy` sigue identificando al empleado o administrador autenticado. El directorio staff devuelve solo id, nombre y email para elegir cliente; permite `q` por nombre/email (máximo 100 caracteres), `page` y `size`, con orden nombre/id. No administra usuarios ni expone hashes/sesiones.

Historial: filtros `status`, `code` exacto sin distinguir mayúsculas, `checkInFrom`/`checkInTo` inclusivos; staff también `customerId` y `roomId`. El cliente que intenta estos filtros reservados recibe 403. `page` 0–100000, `size` 1–100 (20 por defecto), `sort=campo,asc|desc`; campos permitidos createdAt, checkIn, checkOut, code, status, totalAmount, id. Por defecto createdAt,asc, con desempate id. La interfaz pide inicialmente checkIn,desc y permite elegir también entrada ascendente o creación descendente. Selección, conteo y paginación se realizan en PostgreSQL.

Cancelación:

```json
{ "version": 0, "reason": "Cambio de fechas del viaje" }
```

Motivo obligatorio no vacío de hasta 500 caracteres para todos los roles. Solo CONFIRMED puede pasar a CANCELLED. Cliente antes del día de llegada en `hotel.time-zone` (America/Lima por defecto); staff no está sujeto al corte de fecha, pero tampoco cancela CHECKED_IN/CHECKED_OUT. Se guardan motivo, actor y fecha, se incrementa versión y se liberan las fechas por el predicado de estado. No se elimina historia ni se reembolsa dinero.

Errores Problem Details: 400 validación/fecha/importe/política; 401 identidad; 403 permiso/Origin/CSRF; 404 recurso inexistente o ajeno, cliente inválido o habitación inexistente; 409 habitación no elegible/ocupada, idempotencia, estado, versión y cambios incompatibles de catálogo. Capacidad insuficiente o catálogo desactivado después de buscar se considera 409 `ROOM_NOT_AVAILABLE`, para permitir refrescar la selección. Campos desconocidos y asignación masiva se rechazan con 400.

OpenAPI 0.5.0 describe DTO, parámetros, cabecera UUID requerida, roles, snapshot de idempotencia, fechas, versión, cancelación, Location y respuestas 200/201/400/401/403/404/409. Swagger sigue disponible en desarrollo. No incorpora endpoints de pago o recepción.

## Disponibilidad, frontend y auditoría

El contrato de disponibilidad de Fase 4 se conserva. `ReservationOccupancy` incorpora una subconsulta SQL NOT EXISTS con `existing.checkIn < requested.checkOut AND existing.checkOut > requested.checkIn` y los tres estados bloqueantes. Se ejecuta antes del conteo y paginación, sin filtrar páginas en memoria. Crear reserva comprueba esa misma elegibilidad otra vez dentro de la transacción.

Desde `/availability`, un usuario anónimo recibe invitación para iniciar sesión. El autenticado revisa estancia, huéspedes y estimación; staff elige cliente mediante búsqueda paginada. Se evita doble envío mientras hay petición pendiente. Cada intención crea un UUID en memoria y lo conserva con el mismo cuerpo para reintentos, incluyendo renovación de access token y CSRF. La confirmación muestra código e importe acordado. Un 409 ROOM_NOT_AVAILABLE ofrece actualizar disponibilidad.

`/reservations` muestra Mis reservas o Reservas según rol; permite filtros, orden, paginación y detalle. `/reservations/:id` muestra precio histórico, estado y cancelación según `canCancel` calculado en servidor. El formulario exige motivo y envía versión. Tras cancelar se actualizan detalle, historial y disponibilidad; en conflicto se ofrece recargar. Se muestran carga, vacío, errores y reintento. Las consultas privadas incluyen usuario en su clave para aislar cambios de cuenta.

La clave idempotente vive durante el intento de confirmación de esa pantalla, no persiste tras recargar/navegar. Si se pierde una respuesta y se abandona la pantalla, debe comprobarse el historial antes de iniciar una intención nueva. No se añaden tokens a localStorage. No se implementa un almacén persistente de borradores.

`AuditService` registra `RESERVATION_CREATED` y `RESERVATION_CANCELLED` con actor, recurso, ID y requestId en la misma transacción. Replays, rechazos o rollback no duplican eventos de éxito. No se registran cuerpos, claves de autenticación ni contraseñas.

## Archivos y componentes

Nuevos archivos Java en `backend/src/main/java/com/portfolio/reservation/`:

- `reservations/domain/Reservation.java`.
- `reservations/api/ReservationRequests.java`, `ReservationFilter.java`, `ReservationView.java`, `ReservationController.java`.
- `reservations/application/ReservationService.java`, `CustomerDirectory.java`.
- `reservations/infrastructure/ReservationRepository.java`, `ReservationOccupancy.java`, `IdempotencyStore.java`.
- `rooms/application/StayRules.java`, `CatalogReservationGuard.java`.

Otros nuevos: V4, `backend/src/test/java/com/portfolio/reservation/ReservationIT.java`; `frontend/src/features/reservations/api.ts`, `BookingAction.tsx`, `CustomerPicker.tsx`, `ReservationPages.tsx`, `Reservations.test.tsx`; `scripts/reservations-smoke.mjs`; este documento.

Modificados: siete archivos del catálogo/disponibilidad (AvailabilityController/View/Service/Specifications, CatalogService, RoomRepository, RoomTypeRepository), ApiErrors, SystemController, OpenApiConfiguration y application.yml; cuatro suites backend históricas para limpieza de FK y expectativas de nueva fase/esquema; App.tsx, AvailabilityPage.tsx, cliente HTTP de autenticación y su prueba; smoke.mjs, README, plan-inicial y catalog-api.

Los cambios de fases anteriores son integración necesaria: locks y guard del catálogo, ocupación de disponibilidad, traducción de conflicto, rechazo de enteros fraccionarios, versión técnica y limpieza de fixtures. SecurityConfiguration, AuthService, migraciones V1–V3, dependencias y configuración Docker permanecen sin cambios. El cliente HTTP conserva Idempotency-Key durante sus reintentos existentes de autenticación.

## Verificación de la entrega

La suite corregida se ejecutó completa el 10 de septiembre de 2026. Las pruebas no se sustituyen por mocks de PostgreSQL ni por H2.

| Comprobación | Resultado |
|---|---|
| Backend completo, Maven verify / PostgreSQL 18.6 Testcontainers | 107 aprobadas; 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS en 7:24 min |
| Frontend, Vitest | 75 aprobadas en 7 archivos; 0 fallos |
| Build frontend TypeScript/Vite | Correcto |
| ESLint | Correcto, salida 0 |
| PostgreSQL desde cero V1→V4 | Correcto en los contenedores efímeros de la suite; cuatro migraciones aplicadas y esquema validado |
| Actualización con datos V3→V4 | Correcta en ReservationIT, preservando fila histórica; también aplicada al volumen local de Compose |
| Docker reconstruido y saludable | Builds de ambas imágenes correctos; backend, frontend y PostgreSQL healthy; `up --build --wait` terminó con salida 0 |
| Smoke técnico y smoke de reservas | Ambos correctos, salida 0; OpenAPI y Swagger accesibles |
| Secretos y artefactos | 0 coincidencias de secretos reales de `.env`, 0 patrones de credenciales privadas y 0 artefactos entre los 44 archivos de la entrega; sensibles/generados ignorados |

La primera ejecución completa detectó dos problemas: una prueba de rol enviaba un DTO staff incompleto y recibía 400, y Jackson aceptaba huéspedes fraccionarios truncándolos. Se corrigió el cuerpo de esa prueba y se deshabilitó `accept-float-as-int`; la repetición completa pasó también las regresiones.

Desglose backend: AuthenticationIT 17, CatalogIT 18, AvailabilityIT 37, FoundationIT 4 y ReservationIT 31. Frontend: 58 pruebas anteriores y 17 nuevas (16 de reservas y una de conservación de Idempotency-Key durante refresh/CSRF), total 75. Build frontend: TypeScript y Vite, 188 módulos; lint sin errores. Maven genera correctamente el JAR ejecutable. Se observan avisos de Mockito y pools de contextos de test ya cerrados, sin fallos ni pruebas omitidas; el error provocado de auditoría pertenece al caso deliberado de rollback.

Las pruebas de ReservationIT cubren persistencia, propiedad, IDOR, escalada por campos, roles, clientes, elegibilidad, fechas, huéspedes, estados, historial/filtros/paginación, tarifa histórica, guard de catálogo, cancelación por zona horaria, versiones, auditoría, rollback, concurrencia SQL y HTTP, recibos y migración. Las suites existentes de identidad, catálogo, disponibilidad y base permanecen activas.

El smoke de reservas usa Compose local, crea cuenta/catálogo sintéticos identificados por UUID, realiza login → creación/replay → fechas ocupadas → historial/detalle → cancelación → fechas disponibles → logout, y limpia solamente sus propios registros de negocio. No imprime secretos. Los contadores normales del limitador de autenticación pueden permanecer; no son cuentas ni reservas de prueba.

Evidencia integrada: creación 201 CONFIRMED, dos noches a 125,50 PEN con total 251,00; replay 201 idéntico; disponibilidad del tipo sintético 1 → 0; historial y detalle 200; cancelación 200 CANCELLED; disponibilidad vuelve a 1; logout 204. Al terminar se conservó el usuario que ya existía antes del smoke y quedaron cero tipos, habitaciones, reservas y recibos, igual que antes de crear los fixtures. No se borraron datos preexistentes.

Compose registró versión previa 3 y aplicación de una sola migración, `4 - reservations`, el 10 de septiembre de 2026. La consulta de `flyway_schema_history` devuelve V1–V4 con success=true y PostgreSQL confirma la definición GiST con exactamente los tres estados bloqueantes. No hay tablas Payment/Refund ni otras estructuras de Fase 6.

Comandos de verificación utilizados:

```text
docker compose --profile test run --rm backend-tests
npm.cmd test -- --pool=threads --maxWorkers=1  (desde frontend)
npm.cmd run build                           (desde frontend)
npm.cmd run lint                            (desde frontend)
docker compose up --build --wait --wait-timeout 240
docker compose ps
node scripts/smoke.mjs
node scripts/reservations-smoke.mjs
git diff --check
```

Los resultados previos exitosos de pruebas, build y lint se conservaron tras la interrupción; no se repitieron innecesariamente. Las verificaciones finales añadieron el arranque de imágenes, la actualización del volumen local, los dos smoke HTTP, consulta del esquema y revisión de Git. Después de la suite exitosa solo se completó documentación.

## Estado final de Git y revisión de entrega

| Categoría | Modificados | Nuevos | Total |
|---|---:|---:|---:|
| Backend de aplicación, configuración y migración | 11 | 13 | 24 |
| Pruebas backend | 4 | 1 | 5 |
| Frontend y sus pruebas | 4 | 5 | 9 |
| README y documentación | 3 | 1 | 4 |
| Scripts de smoke | 1 | 1 | 2 |
| **Total** | **23** | **21** | **44** |

Rama `main`, upstream `origin/main`. Ambas referencias locales y HEAD siguen en `bc824779cfdccb21015b06ccf56b54782db200ce`; diferencia 0 delante / 0 detrás. El working tree contiene los 44 archivos de esta entrega, todos fuera del staging; índice vacío. No se creó commit ni se hizo push. `git diff --check` no reporta errores.

Revisión de archivos: sin secretos reales coincidentes con la configuración local, sin claves privadas/tokens reales detectados, sin archivos temporales, logs o dependencias/builds entre los cambios. `.env`, variantes sensibles, `.key`, `.pem`, node_modules, dist, target, cobertura y resultados de pruebas siguen ignorados. Las credenciales de fixtures son exclusivamente sintéticas. V1–V3, autenticación backend, dependencias y Compose permanecen intactos. El único ajuste transversal de validación impide convertir números fraccionarios a enteros y fue comprobado por la suite completa.

## Decisiones y límites pendientes

- Confirmación sin pago intencional hasta Fase 6; no existe Payment ni Refund.
- CHECKED_IN/CHECKED_OUT/NO_SHOW son estados preparados, no operaciones de Fase 7.
- Recibos de 24 horas conservados después de vencer: se necesita una política futura de archivo/retención antes de operar a gran escala, preservando la no reutilización de claves.
- Zona horaria por defecto America/Lima y moneda PEN configurables en backend; no hay selector multihotel ni multimoneda.
- No se agregan usuarios privilegiados de demostración. Staff requiere aprovisionamiento controlado; tests usan únicamente fixtures sintéticos aislados.
- La prueba integrada es HTTP y la interfaz tiene pruebas de componentes; no se incorpora una nueva plataforma E2E de navegador.
- No se ha ejecutado CI remota de cambios locales. Esta entrega espera revisión antes de staging, commit, push o cualquier fase posterior.
