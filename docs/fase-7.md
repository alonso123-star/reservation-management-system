# Fase 7 — Recepción

Entrega local para revisión sobre `f967d6dce64a6415af7f05416ac952cab0e62dfd`, Fase 6 aprobada y sincronizada. Esta fase implementa únicamente recepción. No se autoriza staging, commit, push ni Fase 8. Los informes de Fases 1–6 conservan su contenido histórico.

## Transiciones y reglas

| Operación | Origen | Destino | Precondiciones adicionales |
|---|---|---|---|
| Check-in | CONFIRMED | CHECKED_IN | Pago completo aprobado y no reembolsado; fecha local dentro de [checkIn, checkOut) |
| Check-out | CHECKED_IN | CHECKED_OUT | Timestamp de ingreso presente y no posterior al momento actual; salida todavía no registrada |
| No-show | CONFIRMED | NO_SHOW | Instante actual estrictamente posterior al plazo de llegada |

Las otras transiciones se rechazan. Las operaciones de recepción no aceptan `status` ni campos de actor, precio, pago o timestamps del cliente. La cancelación de CONFIRMED conserva el contrato y refund automático de Fases 5–6. No se agregan estados. Reservation y Payment siguen siendo conceptos independientes.

`Reservation.checkIn`, `checkOut` y `noShow` encapsulan las transiciones, con comprobación del estado de origen. El servicio valida las reglas de negocio y devuelve Problem Details controlados antes de invocar esos métodos. No existe un setter de estado expuesto por HTTP.

### Check-in y pago

EMPLEADO o ADMIN puede registrar el ingreso cuando `checkIn <= fechaLocalHotel < checkOut`. El día de entrada está incluido y el de salida excluido. Se reutiliza exactamente `RefundService.isFullyPaid`: existe un APPROVED y no existe refund asociado. La comprobación se ejecuta después de bloquear Reservation, dentro de la transacción de ingreso.

No se admite una reserva impagada, con intentos únicamente DECLINED o con pago reembolsado. La reserva CONFIRMED impagada sigue bloqueando disponibilidad. No se recalculan precios ni se consulta una segunda definición del pago completo.

### No-show: decisión explícita de plazo

No existía una configuración de plazo de llegada. Se incorpora `hotel.reception.arrival-deadline`, configurable con `HOTEL_ARRIVAL_DEADLINE`, con valor inicial **22:00 del día de llegada**. La zona es `hotel.time-zone`, configurable con `HOTEL_TIME_ZONE`, por defecto `America/Lima`. Compose pasa ambas variables al backend y `.env.example` documenta sus valores; no se modifica `.env` real.

Se permite no-show **estrictamente después** de ese instante. En el instante exacto aún se rechaza. No existe tarea automática: requiere una decisión explícita del personal. Una llegada tardía todavía puede registrarse dentro de la estancia si nadie marcó NO_SHOW; ambas operaciones pueden competir y el bloqueo decide cuál se confirma.

La política está centralizada en `ReceptionPolicy`, no en constantes distribuidas ni en el reloj del navegador. La hora inválida o zona inválida impide configurar silenciosamente otra política. `java.time` resuelve una hora inexistente por horario de verano desplazándola hacia adelante y, en una hora ambigua, usa el primer offset; la prueba incluye una zona con cambio horario. America/Lima no requiere ese ajuste en los escenarios probados.

NO_SHOW mantiene reserva, importe y pagos. No genera refund automático. Solo actualiza estado/updatedAt y auditoría; no inventa checkedInAt, checkedOutAt ni una columna nueva para no-show.

### Check-out anticipado

La salida requiere CHECKED_IN, sin una nueva operación de pago. Guarda `checkedOutAt` una sola vez. Conserva checkedInAt, checkIn, checkOut, tarifa, total y moneda históricos. No crea ni modifica Payment/Refund.

Una salida anticipada conserva el rango original completo: CHECKED_OUT sigue bloqueando sus noches. No reduce fechas, no libera noches restantes ni devuelve dinero. La disponibilidad y GiST conservan los estados bloqueantes CONFIRMED, CHECKED_IN y CHECKED_OUT; CANCELLED y NO_SHOW no bloquean.

## Esquema y migraciones

**No se crea V6.** V4 ya incorporó los cinco estados, checked_in_at, checked_out_at, version, updated_at y el predicado de exclusión correcto. V5 ya contiene pagos, refunds y unicidades. La auditoría existente incluye actor, recurso, fecha y requestId; la acción identifica inequívocamente la transición de origen/destino.

V1–V5 permanecen intactas. No se añaden tablas, columnas, índices ni triggers de producción. Los triggers que pausan o hacen fallar transacciones solo se instalan temporalmente en la base efímera de pruebas y se eliminan al terminar cada caso.

## Concurrencia, repetición y atomicidad

`ReceptionService.transition` está autorizado para EMPLEADO/ADMIN y usa una transacción corta:

1. `ReservationAccess.lockOwned` obtiene la misma fila con PESSIMISTIC_WRITE que pago/cancelación, sin precargarla.
2. Comprueba la versión enviada y el estado actual después de obtener el bloqueo.
3. Evalúa momento, pago o plazo según operación.
4. Aplica la transición y hace flush.
5. Registra auditoría y confirma todo conjuntamente.

El bloqueo se mantiene hasta commit/rollback. No hay locks Java, efectos externos ni cambios de orden de bloqueo de catálogo. Una excepción de auditoría revierte estado, timestamps y versión. Si check-in gana, cancelar se rechaza y el pago permanece aprobado sin refund; si cancelar gana, check-in se rechaza y la cancelación conserva su refund atómico.

El contrato aprobado exige Idempotency-Key para creación y pagos, no para recepción. Extender sus operaciones requeriría ampliar el CHECK de V5 mediante una migración y conservar snapshots adicionales. Aquí no es necesario: se reutiliza la protección de comandos existente en cancelación, **versión + bloqueo + transición irreversible**, sin un segundo almacén. Se envía `{ "version": 0 }` y no se requiere Idempotency-Key. Un retry posterior al éxito devuelve 409 STALE_VERSION o INVALID_RESERVATION_STATE y no duplica timestamps, transición ni auditoría. Si toda la transacción falla, puede reintentarse después de consultar el estado.

## Contrato REST, permisos y errores

| Ruta POST | Roles | Éxito |
|---|---|---|
| `/api/v1/reservations/{id}/check-in` | EMPLEADO, ADMIN | 200 ReservationView actualizado |
| `/api/v1/reservations/{id}/check-out` | EMPLEADO, ADMIN | 200 ReservationView actualizado |
| `/api/v1/reservations/{id}/no-show` | EMPLEADO, ADMIN | 200 ReservationView actualizado |

Cuerpo obligatorio, versión entera no negativa:

```json
{ "version": 0 }
```

CLIENTE recibe 403 para una operación de recepción con DTO válido, incluso si es propietario. Anónimo recibe 401; reserva inexistente para personal, 404. Se mantienen JWT, comprobación de sesión, Origin y CSRF de Fase 2. No se modifica SecurityConfiguration ni se agregan rutas de administración de usuarios/roles.

Campos desconocidos, UUID/cuerpo/versión inválidos devuelven 400. Los conflictos devuelven 409 con los siguientes códigos:

| Código | Motivo |
|---|---|
| STALE_VERSION | Otra operación cambió la versión |
| INVALID_RESERVATION_STATE | Estado de origen incompatible o transición repetida |
| RESERVATION_NOT_FULLY_PAID | Check-in sin pago completo vigente |
| CHECK_IN_OUTSIDE_STAY | Fecha del hotel fuera del periodo de ingreso |
| ARRIVAL_DEADLINE_NOT_PASSED | Todavía no pasó el plazo de no-show |
| INVALID_RECEPTION_TIMESTAMP | Salida sin ingreso registrado o con cronología incoherente |

Problem Details mantiene requestId y no expone SQL ni stack traces. OpenAPI 0.7.0 documenta roles, cuerpos, ejemplos, respuestas, errores y políticas de las tres rutas.

GET `/api/v1/reservations/{id}` conserva sus campos anteriores y añade `reception` en el detalle del personal. Contiene customerName, hotelTimeZone, hotelDate, evaluatedAt, arrivalDeadline, fullyPaid, canCheckIn, canCheckOut y canNoShow. Para CLIENTE es null. Historial y snapshots de creación conservan reception=null; no calculan capacidades ni pagos por cada fila de la página. Las respuestas antiguas de idempotencia se deserializan con este campo opcional null.

Las capacidades son orientativas y pueden quedar desactualizadas después de leerlas; cada comando vuelve a comprobarlas bajo bloqueo. No son una concesión de permisos ni un bloqueo de disponibilidad.

## Frontend mínimo de recepción

`/staff/reception` está protegido por RoleGuard y aparece en la navegación de EMPLEADO/ADMIN. Reutiliza GET reservations con filtros de estado, código y llegada, orden ascendente por llegada y paginación de seis filas. Ofrece vistas de confirmadas por revisar —llegadas y posibles no-show— y huéspedes alojados. No presenta todas las confirmadas como no-show válidos: la elegibilidad exacta se comprueba en el detalle.

El listado muestra código, identificador del cliente, habitación/tipo, estancia, huéspedes, estado e importe. Su enlace abre el detalle existente, donde se muestra el nombre del huésped, pago e historial, hora/zona del hotel y acciones permitidas. Así se evita cargar pagos por cada fila del listado.

`ReceptionPanel` usa capacidades calculadas en backend. El check-in impagado no se ofrece y se explica por qué. Las otras acciones se ocultan cuando no corresponden. Cada operación tiene una confirmación con reserva, huésped, habitación, fechas, estado y pago; check-out/no-show explican sus consecuencias. No se presentan importes o fechas recalculados.

Los botones quedan deshabilitados durante el envío; se manda únicamente la versión. Ante 409 o respuesta incierta, se pide actualizar el estado y no se reintenta automáticamente una transición. El cambio de reserva/usuario/versión reinicia la confirmación. Tras éxito se refrescan detalle, listados, pagos y disponibilidad. El detalle de personal refresca capacidades cada 30 segundos y también ofrece actualización manual; pagar invalida su detalle para habilitar check-in cuando corresponda.

No se añaden campos a localStorage ni nuevas credenciales. CLIENTE no tiene controles ni acceso a la vista de recepción. Los cambios en RoleGuard añaden un mensaje configurable conservando el mensaje anterior del catálogo.

## Auditoría

- RESERVATION_CHECKED_IN: CONFIRMED → CHECKED_IN.
- RESERVATION_CHECKED_OUT: CHECKED_IN → CHECKED_OUT.
- RESERVATION_NO_SHOW: CONFIRMED → NO_SHOW.

Se utiliza AuditService, con actor autenticado, reservationId, occurredAt y requestId. La acción identifica la transición; no se necesita una columna nueva ni se almacenan cuerpos, JWT, refresh tokens, contraseñas o datos de tarjetas. Los intentos rechazados y retries no crean eventos de éxito.

## Pruebas y resultados

La nueva suite ReceptionIT usa PostgreSQL real, servidor HTTP real y Clock sustituible únicamente en test. Incluye estados inválidos, permisos, propiedad, sesión revocada, campos manipulados, importes históricos, límites temporales en America/Lima, configuración de plazo/DST, auditoría y rollback. Las pruebas de checkout anticipado y no-show consultan la disponibilidad real.

Las carreras pausan la primera transacción HTTP mediante un advisory lock en un trigger de auditoría de test mientras conserva el bloqueo de Reservation. Se observa a la segunda esperando en pg_stat_activity antes de soltar la primera. Se verifica en BD una versión incrementada, una transición/evento y timestamps consistentes:

- Check-in → cancelación: gana ingreso; cancelación 409, sin refund.
- Cancelación → check-in: gana cancelación; ingreso 409, un refund completo.
- Check-in → no-show y orden contrario: solo gana la primera, sin timestamps contradictorios.
- Dos check-ins y dos check-outs: 200/409 y un solo evento.

El E2E determinista es HTTP dentro de ReceptionIT: registro, login, búsqueda, creación, rechazo/aprobación de pago, avance controlado del Clock, login de personal, check-in y checkout anticipado; otra reserva recorre no-show y disponibilidad. No hay mocks de pago/BD/HTTP ni una ruta de producción para alterar el reloj. La interfaz se prueba mediante Vitest/Testing Library; el repositorio no tenía infraestructura E2E de navegador. Esta entrega no afirma una ejecución Playwright.

| Comprobación | Resultado |
|---|---|
| ReceptionIT enfocada | 43 aprobadas, 0 fallos/errores/omitidas, BUILD SUCCESS |
| Backend completo y regresiones 2–6 | 175 aprobadas: Authentication 17, Catalog 18, Availability 37, Reservation 31, Payment 25, Foundation 4 y Reception 43; 0 fallos, errores, omitidas o flakes |
| Frontend completo | 109 aprobadas en 9 archivos; incluye 18 nuevas de recepción; 0 fallos |
| Build backend | Imagen reconstruida con Java 21; Maven package / Spring Boot repackage: BUILD SUCCESS |
| Build frontend | Correcto; TypeScript/Vite, 192 módulos |
| Lint final | ESLint correcto, exit 0 |
| Flyway desde cero | ReceptionIT aplicó V1–V5 y validó el esquema; sin V6 |
| Docker / base V5 existente | Compose reconstruido; PostgreSQL, backend y frontend healthy; Flyway validó 5 migraciones y confirmó esquema V5 al día |
| Smoke técnico | Frontend/assets/proxy, readiness con PostgreSQL, catálogo, disponibilidad, inventario protegido, OpenAPI y Swagger: correcto |
| Smoke de regresión | Reserva/replay/historial/detalle, DECLINED/replay, APPROVED/replay, doble pago rechazado, cancelación con un refund completo y disponibilidad: correcto |
| Smoke de recepción | Registro/login → búsqueda → reserva → pago → check-in → salida anticipada; segunda reserva → no-show → disponibilidad; permisos y OpenAPI: correcto |
| OpenAPI / Swagger reconstruidos | Versión 0.7.0; tres POST con bearerAuth y respuestas 200/400/401/403/404/409; Swagger HTTP 200 |
| Secretos / artefactos | Sin secretos reales ni archivos accidentales en los cambios; configuración local y artefactos siguen ignorados |
| Git / formato | 30 archivos locales: 20 modificados y 10 nuevos; staging vacío; diff --check sin errores |

La verificación integrada final se completó el 11 de septiembre de 2026. Tras la interrupción se recuperaron los siete XML y `failsafe-summary.xml`: las 175 pruebas terminaron sin errores. Las suites backend/frontend ya aprobadas no se repitieron; los cambios posteriores se limitaron a documentación y al smoke independiente. El build de las imágenes no sustituye esas suites: Maven empaqueta con tests omitidos en esa etapa, después de las pruebas de integración.

Se revisaron los seis escenarios concurrentes aprobados: ambos órdenes de check-in/cancelación, ambos de check-in/no-show, doble check-in y doble check-out. Todos verifican 200/409, estado final, versión, timestamps, un solo evento y refund únicamente cuando gana la cancelación.

El primer smoke contra el reloj real detectó que comparaba literalmente el Instant de la respuesta inmediata (nanosegundos Java) con el valor posteriormente leído de PostgreSQL (microsegundos). Se corrigió solamente el script para leer el ingreso persistido antes de la salida y comparar ese valor sin pérdida de precisión adicional. El smoke completo pasó después; no se cambió código de producción ni se invalidaron las suites existentes.

Los contadores de negocio antes y después de los smokes coinciden: un usuario preexistente, cero tipos, habitaciones, reservas, pagos, refunds y recibos de idempotencia. No quedaron triggers de recepción de test en la base local. No se borró el volumen de desarrollo para verificar Flyway: el arranque desde cero se comprobó en PostgreSQL efímero de Testcontainers. No aplica un upgrade V5 → V6 porque no existe una nueva migración; la aplicación reconstruida arrancó correctamente sobre V5 existente.

La primera invocación Maven falló antes de compilar por el argumento `-Dit.test` sin comillas en PowerShell; con comillas la suite pasó. La primera suite frontend detectó una espera insuficiente en una prueba nueva y un timeout de inicio de worker; se corrigió la espera y se ejecutan las suites pesadas por separado para reducir carga.

Comandos desde la raíz salvo indicación:

```text
docker compose --profile test run --rm backend-tests '-Dit.test=ReceptionIT'
docker compose --profile test run --rm backend-tests
npm.cmd test -- --pool=threads --maxWorkers=1  (frontend)
npm.cmd run build                           (frontend)
npm.cmd run lint                            (frontend)
docker compose up --build --wait --wait-timeout 240
node scripts/smoke.mjs
node scripts/reservations-smoke.mjs --payments
node scripts/reception-smoke.mjs
git diff --check
```

`reception-smoke.mjs` es exclusivo de Compose local en puerto 3000: crea dos cuentas sintéticas, habilita EMPLEADO solo en su cuenta de fixture, prepara catálogo aislado y realiza el flujo por HTTP. Usa fechas relativas al día configurado del hotel, verificadas con el detalle del servidor; el escenario con avance de Clock se cubre en ReceptionIT. Limpia exclusivamente UUIDs/cuentas creados por ese script, sin borrar ni promover usuarios preexistentes, y no imprime secretos. Los contadores normales del limitador de autenticación pueden permanecer.

## Archivos y límites

Nuevos componentes: ReceptionController, ReceptionView, ReceptionPolicy, ReceptionService, ReceptionIT, ReceptionPage, ReceptionPanel y su suite, script de smoke y este informe. Se integran Reservation/ReservationView/ReservationService, detalle y navegación frontend, invalidación tras pago, documentación OpenAPI y versión técnica. Las suites históricas solo actualizan expectativas de fase/rutas; conservan los casos anteriores. Compose y `.env.example` exponen la política temporal; no se modifica `.env` real.

Inventario final (30 archivos):

| Categoría | Nuevos | Modificados |
|---|---|---|
| Backend: recepción y reservas | ReceptionController.java, ReceptionView.java, ReceptionPolicy.java, ReceptionService.java | ReservationController.java, ReservationView.java, ReservationService.java, Reservation.java |
| Backend: pruebas y contrato técnico | ReceptionIT.java | FoundationIT.java, PaymentIT.java, ReservationIT.java, SystemController.java, OpenApiConfiguration.java |
| Frontend | ReceptionPage.tsx, ReceptionPanel.tsx, Reception.test.tsx | App.tsx, PaymentPanel.tsx, ReservationPages.tsx, reservations/api.ts, RoleGuard.tsx |
| Configuración | — | .env.example, compose.yaml, application.yml |
| Verificación HTTP | scripts/reception-smoke.mjs | scripts/smoke.mjs |
| Documentación | docs/fase-7.md | README.md, docs/plan-inicial.md |

Al retomar había 28 archivos locales. Los dos adicionales son README y plan-inicial, cuya actualización mínima estaba solicitada en la entrega: se actualizó el estado de aprobación y los enlaces/instrucciones; la planificación original permanece conservada.

La revisión no encontró claves privadas, tokens reales, datos de tarjeta ni credenciales locales copiadas. Las credenciales explícitas de ReceptionIT son fixtures sintéticos para su PostgreSQL efímero; el smoke genera sus contraseñas aleatoriamente en memoria. `.env.example` conserva su marcador anterior y una clave JWT vacía. `.env`, sus variantes sensibles, claves, logs, node_modules, target, dist y coverage siguen excluidos por las reglas de ignore. No se modificaron la implementación de identidad/pagos ni SecurityConfiguration; la integración frontend de pagos solo invalida el detalle tras pagar. V1–V5 no tienen diferencias con HEAD. No hay archivos nuevos de producción fuera del módulo de recepción.

Estado final: rama `main`, HEAD `f967d6dce64a6415af7f05416ac952cab0e62dfd`; upstream `origin/main`. Ambas referencias locales apuntan al mismo commit, con 0 ahead / 0 behind. Working tree con los 30 archivos citados y staging vacío. `git diff --check` y la comprobación de espacios finales en los archivos nuevos no reportan errores. No se ejecutó staging, commit, push ni una verificación remota nueva en esta entrega.

No se implementan administración de usuarios/roles, dashboard, métricas, consulta visual de auditoría, informes, notificaciones, pagos reales, modificaciones de fechas, múltiples habitaciones ni datos demo finales. No se inicia Fase 8 ni Fase 9. La consulta de pago completo y la exclusión histórica continúan intactas. Se requiere revisión antes de staging/commit/push.
