# Fase 6 — Pagos simulados

Entrega local para revisión. Base aprobada y sincronizada: `689b0682f3377bf2d2119cf82fad8455faab57a1` (Fase 5). No se hace staging, commit ni push. No se implementa Fase 7. Los documentos de fases anteriores conservan el registro histórico de sus entregas.

## Alcance y decisiones

Se registran intentos de pago simulado del importe completo de una reserva, se consulta su historial y se crea automáticamente un reembolso completo al cancelar una reserva con pago aprobado. PostgreSQL garantiza como máximo un pago aprobado por reserva y un reembolso por pago.

Payment y Refund son históricos: no hay borrado durante operaciones normales. El estado de Reservation sigue siendo independiente; no existe un estado PAID en Reservation. CONFIRMED puede estar impagada y continúa bloqueando disponibilidad. Pagos/reembolsos no cambian directamente la ocupación; la cancelación conserva las reglas de Fase 5.

No hay proveedor externo, tarjetas, CVV, claves de pasarela, webhooks, checkout real, pagos parciales, depósitos, cuotas, impuestos nuevos ni múltiples cobros aprobados. Tampoco hay recepción, check-in/check-out/no-show operativo, dashboard, métricas, informes, emails o SMS.

## Modelo y migración V5

`backend/src/main/resources/db/migration/V5__simulated_payments.sql` añade las estructuras de esta fase y extiende los recibos de idempotencia. V1, V2, V3 y V4 permanecen intactas. La exclusión GiST de reservas y sus tres estados bloqueantes no cambian.

### Payment / tabla `payments`

Cada fila representa un intento persistido, incluso cuando es rechazado.

| Columna | Tipo y restricción |
|---|---|
| id | UUID PK, generado por servidor |
| reservation_id | UUID NOT NULL, FK reservations |
| amount | NUMERIC(12,2) NOT NULL, CHECK > 0 |
| currency | VARCHAR(3) NOT NULL, CHECK tres letras mayúsculas |
| result | VARCHAR(16) NOT NULL, CHECK APPROVED / DECLINED |
| simulated_reference | VARCHAR(50) NOT NULL UNIQUE, prefijo SIM-P- + UUID |
| actor_id | UUID NOT NULL, FK users; usuario autenticado |
| created_at | TIMESTAMPTZ NOT NULL |

Resultados finales mínimos: **APPROVED** o **DECLINED**. No existen PENDING ni otros estados de pasarela innecesarios. Importe y moneda se copian de `Reservation.totalAmount` y `Reservation.currency`, nunca del frontend ni de la tarifa actual del catálogo. Se usan BigDecimal y NUMERIC(12,2); no float/double para los cálculos monetarios.

Protección final de doble cobro:

```sql
CREATE UNIQUE INDEX uq_payments_approved_reservation
    ON payments(reservation_id) WHERE result='APPROVED';
```

Hay además un índice `(reservation_id, created_at, id)` para historial ordenado y conteo por reserva. No se añaden índices individuales redundantes por fecha o resultado sin una consulta que los necesite.

### Refund / tabla `refunds`

| Columna | Tipo y restricción |
|---|---|
| id | UUID PK, generado por servidor |
| payment_id | UUID NOT NULL, FK payments, UNIQUE |
| amount | NUMERIC(12,2) NOT NULL, CHECK > 0 |
| currency | VARCHAR(3) NOT NULL, CHECK tres letras mayúsculas |
| reason | VARCHAR(500) NOT NULL, CHECK motivo no vacío |
| actor_id | UUID NOT NULL, FK users; actor de cancelación |
| created_at | TIMESTAMPTZ NOT NULL |

```sql
CONSTRAINT uq_refunds_payment UNIQUE(payment_id)
```

El constructor de Refund rechaza un Payment no aprobado y copia íntegramente su amount/currency; no acepta un importe alternativo. La fila representa el reembolso simulado completado, sin procesamiento externo pendiente. La aplicación garantiza importe completo y pago APPROVED; la base refuerza FK, importes positivos, moneda y unicidad. El índice UNIQUE de payment_id sirve también para consultar el reembolso; no se duplica.

## Simulador determinista

`PaymentSimulator` es la abstracción del módulo payments. `DeterministicPaymentSimulator` decide únicamente a partir del número de intentos previamente persistidos de esa reserva, leído por el servicio bajo el bloqueo:

1. Cero intentos anteriores: resultado DECLINED.
2. Uno o más intentos anteriores: resultado APPROVED, siempre que aún sea legal intentar pagar.

Es un escenario sintético deliberado para mostrar rechazo, reintento explícito y aprobación sin pedir datos de tarjeta ni un resultado al cliente. No utiliza aleatoriedad para decidir el resultado. Un reintento con la misma clave reproduce el recibo anterior y no incrementa el contador ni vuelve a invocar el simulador. Una nueva intención explícita utiliza una clave nueva. Después de APPROVED, cualquier nueva intención se rechaza antes de ejecutar el simulador.

Las pruebas pueden sustituir la abstracción por un escenario controlado de rechazos repetidos; esto no se expone en la API. El servicio permite conservar varios DECLINED. No existe un campo HTTP scenario/result. Una futura integración real debe resolver sus propios fallos y efectos externos dentro del módulo payments; las garantías atómicas descritas aquí corresponden al simulador local.

## Pago, locking y cancelación

Solo una reserva **CONFIRMED** sin pago aprobado es pagable. CANCELLED y los estados preparados CHECKED_IN/CHECKED_OUT/NO_SHOW se rechazan con 409 RESERVATION_NOT_PAYABLE. Así se mantiene la precondición de pago completo anterior a recepción sin implementar sus operaciones.

Flujo transaccional de pago:

1. Leer Reservation con PESSIMISTIC_WRITE y comprobar ownership con `ReservationAccess.lockOwned`.
2. Reclamar el recibo de operación PAY_RESERVATION; si existe resultado válido, reproducirlo.
3. Validar el estado actual y ausencia de APPROVED.
4. Obtener amount/currency históricos y evaluar el simulador.
5. Persistir Payment y hacer flush para aplicar las restricciones PostgreSQL.
6. Registrar auditoría y completar el recibo.
7. Confirmar todo en una sola transacción.

No se precarga la entidad antes del SELECT con bloqueo, evitando reutilizar un estado previo a una cancelación concurrente. El bloqueo se mantiene hasta commit/rollback; no hay lock en memoria Java.

La cancelación de Fase 5 ahora usa exactamente el mismo bloqueo de Reservation y conserva comprobación de versión, ownership, estado, motivo y corte de fecha en la zona del hotel. Después de validar, `RefundService.refundForCancellation` busca el pago aprobado, crea el reembolso completo si corresponde y registra REFUND_CREATED. Se cambia la reserva a CANCELLED y se registra RESERVATION_CANCELLED en esa misma transacción.

Si gana el pago, la cancelación ve el aprobado y lo reembolsa. Si gana la cancelación, el pago ve CANCELLED y se rechaza. Una excepción de persistencia o auditoría revierte íntegramente la operación. No puede confirmarse por estas rutas una cancelación parcial con pago aprobado sin el reembolso correspondiente.

Orden relevante: Reservation → recibo de pago → Payment; cancelación: Reservation → Payment/Refund. No se bloquean habitaciones o tipos desde pagos. Crear reservas mantiene su protocolo de Fase 5. El control optimista de cancelación sigue rechazando formularios antiguos aunque exista bloqueo pesimista.

Repetir una cancelación devuelve el conflicto de estado/versión de Fase 5; no crea otro Refund. Además de la comprobación del servicio, UNIQUE(payment_id) impide físicamente el duplicado. No existe endpoint manual de reembolso.

`RefundService.isFullyPaid(reservationId)` determina si existe APPROVED sin Refund. Es una consulta interna disponible para la precondición futura de recepción; no realiza check-in ni concede permisos. Fase 7 deberá comprobarla junto con estado/fechas/autorización mientras tenga bloqueada la reserva.

## Idempotencia reutilizada

Se mantiene `IdempotencyStore`, la tabla, unicidad, vencimiento de 24 horas y política de claves no reutilizables de Fase 5. Se añade la operación **PAY_RESERVATION**, una columna payment_id con FK y deserialización tipada del snapshot. El recibo de pago conserva también reservation_id; los recibos históricos de creación siguen válidos con payment_id NULL.

V5 amplía el CHECK de operation y exige coherencia entre payment_id y respuesta para operaciones de pago. Las creaciones de reservas mantienen sus métodos públicos existentes, que delegan en la implementación común. No hay un almacén paralelo ni una nueva política de tokens.

El contenido lógico del pago es el UUID de la reserva y la versión fija del contrato, pues el cuerpo válido es `{}`. El hash es SHA-256 de `payment:v1:<reservationId>`. El ámbito único continúa siendo actor + operación + clave; usar la misma clave del actor para otra reserva produce 409 IDEMPOTENCY_KEY_REUSED. Las operaciones de creación y pago tienen ámbitos distintos.

Misma clave/reserva reproduce exactamente el PaymentView original con 201, incluyendo DECLINED. El snapshot de un APPROVED anterior a la cancelación conserva refund=null: GET payments muestra el estado actual y el reembolso. Tras 24 horas, la clave devuelve IDEMPOTENCY_KEY_EXPIRED y no se vuelve a ejecutar silenciosamente. Un fallo que revierte toda la transacción no deja intento, auditoría de éxito ni recibo incompleto.

## API, permisos y errores

| Ruta | Función | Respuesta |
|---|---|---|
| POST `/api/v1/reservations/{id}/payments` | Nuevo intento simulado o replay | 201 PaymentView; Location apunta al historial |
| GET `/api/v1/reservations/{id}/payments` | Estado derivado e historial con refunds | 200 PaymentHistory |
| POST `/api/v1/reservations/{id}/cancel` | Cancelación con refund automático cuando existe APPROVED | 200 ReservationView, contrato de Fase 5 preservado |

Las tres rutas permiten al CLIENTE propietario o EMPLEADO/ADMIN. Reserva inexistente o ajena para CLIENTE devuelve 404. La seguridad se aplica en servicios con `@PreAuthorize`; `ReservationAccess` comparte la comprobación de propiedad entre reservas y pagos. El usuario autenticado se obtiene del JWT validado y de la comprobación de sesión existente, nunca del cuerpo.

POST payments requiere **Idempotency-Key UUID** y cuerpo JSON vacío:

```json
{}
```

Se rechazan amount, currency, result, status, simulatedReference, actorId, createdBy, customerId, refund y cualquier campo desconocido. No se admiten escenarios enviados por el cliente. JWT, sesiones, Origin y CSRF conservan su configuración de Fase 2.

PaymentView incluye id, reservationId, amount, currency, result, simulatedReference, actorId, createdAt y refund opcional. RefundView incluye id, paymentId, amount, currency, reason, actorId y createdAt. No se exponen entidades JPA ni datos de identidad sensibles.

GET admite `page` (0–100000), `size` (1–100, 20 por defecto) y `sort=createdAt|id,asc|desc`. El orden predeterminado es createdAt,asc con id de desempate. La interfaz utiliza createdAt,desc. Selección, conteo y paginación se realizan en PostgreSQL; los refunds de la página se cargan en una consulta agrupada.

PaymentHistory contiene `settlement` derivado (UNPAID, PAID o REFUNDED), `amountDue`, `currency`, `canPay` y `attempts` con el PageView existente. Settlement describe el historial monetario, no altera Reservation.status. amountDue es el total histórico si puede pagarse y cero si no es pagable; UNPAID en una reserva cancelada no significa que pueda cobrarse.

| HTTP | Casos |
|---|---|
| 400 | Cuerpo, clave UUID, campos o paginación inválidos; cabecera/cuerpo ausentes |
| 401 | Sin autenticación o sesión inválida |
| 403 | Rol, Origin o CSRF denegado |
| 404 | Reserva inexistente o ajena |
| 409 | RESERVATION_ALREADY_PAID, RESERVATION_NOT_PAYABLE, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_EXPIRED; REFUND_ALREADY_EXISTS si se alcanza la barrera de BD; versión/estado de cancelación |

Problem Details conserva código, detalle y requestId sin SQL ni stack traces. OpenAPI 0.6.0 documenta DTO, roles, cuerpo vacío, clave obligatoria, resultados, ejemplos, paginación, códigos y refund automático de cancelación.

## Frontend y auditoría

`PaymentPanel` se integra en el detalle de reserva. Muestra estado derivado, total pendiente, moneda, aviso de simulación, historial paginado, referencias y reembolso asociado. No tiene campos de tarjeta ni botón manual de reembolso. Propietario y personal usan el mismo flujo autorizado por servidor; no se añade una pantalla de recepción.

Cada intención conserva su UUID durante errores de red y reintentos. Se reutiliza el cliente HTTP de Fase 5, que también conserva la clave al renovar JWT/sesión/CSRF. El botón se deshabilita mientras se envía. Después de DECLINED, el usuario puede solicitar explícitamente un nuevo intento con nueva clave. Un 409 ofrece actualizar pagos sin crear automáticamente otra intención.

La clave vive en memoria durante ese intento y se descarta al cambiar usuario/reserva o abandonar la pantalla. Si se abandona una operación con respuesta incierta, se debe consultar el historial antes de iniciar una nueva intención. Las consultas incluyen usuario y reserva en sus claves. Cancelar invalida pagos además de detalle/historial de reservas y disponibilidad; se muestra CANCELLED, el APPROVED histórico y el Refund completo.

Se registran PAYMENT_ATTEMPTED y exactamente un resultado PAYMENT_APPROVED/PAYMENT_DECLINED por intento persistido. El replay no repite auditoría. REFUND_CREATED y RESERVATION_CANCELLED se confirman conjuntamente. La auditoría contiene actor, acción, recurso, ID y requestId; no guarda cuerpos, tokens, contraseñas ni supuestos datos de tarjeta.

## Componentes y cambios sobre fases anteriores

Nuevos en el módulo backend payments: Payment, Refund y PaymentSimulator; DeterministicPaymentSimulator, PaymentRepository y RefundRepository; PaymentService y RefundService; PaymentController, PaymentView y PaymentHistory. Se añade ReservationAccess para reutilizar ownership y V5 para el esquema. PaymentIT contiene las verificaciones nuevas de PostgreSQL y HTTP.

ReservationRepository añade lockById; ReservationService integra acceso compartido y refund en cancelación; IdempotencyStore se generaliza conservando las llamadas existentes. ApiErrors reconoce las dos restricciones monetarias; ReservationController actualiza el contrato de cancelación; SystemController/OpenApiConfiguration anuncian la fase actual.

Las cinco suites backend anteriores actualizan limpieza de FK y expectativas de esquema/rutas sin quitar sus casos. La prueba histórica de upgrade V3→V4 fija ahora target=4 y permanece; PaymentIT verifica V4→V5. Frontend añade payments/api.ts, PaymentPanel.tsx y PaymentPanel.test.tsx. ReservationPages integra el panel y refresca pagos al cancelar; Reservations.test aísla el panel nuevo, cuya suite contiene una prueba integrada real de componentes para cancelación pagada.

El smoke existente acepta `--payments` y limpia también sus refunds/pagos. Se actualizan README, estado del plan y documentación de esta entrega. No se añaden dependencias, cambios de seguridad de identidad ni modificaciones a V1–V4.

## Pruebas y evidencia

| Verificación | Resultado |
|---|---|
| Regresión enfocada ReservationIT | 31 aprobadas; 0 fallos/errores/omitidas; BUILD SUCCESS |
| Suite backend completa | 132 aprobadas; 0 fallos, errores u omitidas; BUILD SUCCESS (107 anteriores + 25 de PaymentIT) |
| Suite frontend completa | 91 aprobadas en 8 archivos; 0 fallos |
| Build frontend TypeScript/Vite | Correcto, 190 módulos |
| ESLint | Correcto, salida 0 |
| Build backend | Maven verify y empaquetado de imagen Java 21 correctos |
| Flyway desde cero | PostgreSQL efímero: cinco migraciones aplicadas, esquema V5 y pruebas correctas |
| Upgrade V4→V5 | PaymentIT aprobado: una migración, validate correcto, reserva histórica y recibo anterior conservados; Compose local también actualizado de V4 a V5 |
| Docker | Reconstrucción correcta; backend, frontend y PostgreSQL healthy; compose up --wait terminó con salida 0 |
| Smoke técnico HTTP | Correcto: frontend/assets, proxy, readiness, catálogo, disponibilidad/validaciones, rutas protegidas, OpenAPI y Swagger |
| Smoke reservas/pagos/refunds | Correcto: rechazo/replay, aprobación/replay, doble pago bloqueado, historial, cancelación, un refund completo, pago posterior bloqueado y disponibilidad recuperada |
| Revisión de secretos, alcance y Git | Sin hallazgos de secretos reales o artefactos entre los 36 cambios; diff --check sin errores; staging vacío; V1–V4 intactas |

Las pruebas nuevas cubren aprobación/rechazo determinista, importes históricos, moneda, actores y tres roles, IDOR, asignación masiva, estados no pagables, múltiples rechazos, historial y paginación, claves iguales/distintas, replay de DECLINED/APPROVED y snapshots anteriores al refund, expiración, auditoría y rollback.

Pruebas críticas de concurrencia con PostgreSQL real:

- Dos POST con claves distintas después de un rechazo compiten: como máximo un APPROVED, el otro obtiene conflicto.
- Dos POST con la misma clave producen una sola operación, un recibo y un par de eventos.
- Dos conexiones JDBC independientes insertan APPROVED sin locks del servicio. La segunda esperó el índice único hasta que la primera confirmó y recibió SQLSTATE 23505. Se comprobó exactamente un APPROVED persistido.
- Pago/cancelación se prueban en ambos órdenes. Un trigger de prueba pausa al ganador mediante un advisory lock mientras mantiene el bloqueo real de Reservation; se observa al competidor esperando en pg_stat_activity antes de liberar al ganador. Todo el control es exclusivo de la base efímera del test.
- Dos cancelaciones concurrentes de una reserva pagada generan un solo Refund. Otra prueba con dos conexiones independientes fuerza la colisión de UNIQUE(payment_id), espera real y SQLSTATE 23505.
- Fallo provocado de auditoría de pago revierte intento/recibo/eventos; fallo de auditoría del refund revierte también la cancelación y conserva la reserva confirmada con su pago.

Frontend añade 16 casos: estados, importe, cuerpo seguro, aprobación/rechazo, nueva intención, loading, retry con misma clave, 409, historial/refund, error de consulta, IDOR, anonimato, tres roles, paginación, estado no pagable y cancelación pagada con actualización del panel. Los 75 casos anteriores permanecen.

Comandos de verificación:

```text
docker compose --profile test run --rm backend-tests
npm.cmd test -- --pool=threads --maxWorkers=1  (desde frontend)
npm.cmd run build                           (desde frontend)
npm.cmd run lint                            (desde frontend)
docker compose up --build --wait --wait-timeout 240
node scripts/smoke.mjs
node scripts/reservations-smoke.mjs --payments
git diff --check
```

El smoke usa exclusivamente Compose local en puerto 3000 y datos sintéticos identificados por UUID. Recorre registro/login, catálogo/disponibilidad, reserva, DECLINED/replay, nueva intención APPROVED/replay, doble pago rechazado, historial, cancelación pagada, refund completo, repetición rechazada, recuperación de disponibilidad y OpenAPI/logout. Finalmente limpia solamente sus propios datos y conserva cuentas/catálogo preexistentes. No imprime credenciales ni tokens.

Verificación final realizada el 10 de septiembre de 2026 (America/Lima). No se repitieron las suites ya aprobadas al retomar la fase; se recuperaron los informes de Maven y se conservaron los resultados del frontend. La reconstrucción de imágenes sí ejecutó sus pasos normales de compilación. No se modificó código después de esas suites; se completó la documentación.

El upgrade de Compose registró esquema inicial 4, validación de cinco migraciones y aplicación exitosa de V5. Se consultó flyway_schema_history: versiones 1–5 con success=true. Antes y después del smoke, los conteos fueron una cuenta preexistente y cero tipos, habitaciones, reservas, pagos, refunds y recibos; no quedaron fixtures de negocio. Se verificaron en PostgreSQL los índices UNIQUE de pago aprobado y refund. Los tres servicios permanecieron healthy.

Las regresiones backend conservan AuthenticationIT (17), CatalogIT (18), AvailabilityIT (37), ReservationIT (31) y FoundationIT (4), todas aprobadas. Las pruebas de concurrencia confirmaron ambos órdenes: pago primero permite cancelar con un refund; cancelación primero rechaza el pago. No hubo fallos pendientes. La verificación de interfaz combina Vitest y HTTP contra el frontend construido; no se presenta como una prueba E2E de navegador.

## Revisión de alcance y estado final de Git

Los 36 archivos de la entrega se distribuyen así:

| Categoría | Modificados | Nuevos | Total |
|---|---:|---:|---:|
| Backend de aplicación y V5 | 7 | 13 | 20 |
| Pruebas backend | 5 | 1 | 6 |
| Frontend y pruebas | 2 | 3 | 5 |
| README, plan y documento de Fase 6 | 2 | 1 | 3 |
| Scripts de smoke | 2 | 0 | 2 |
| Total | 18 | 18 | 36 |

No se encontraron archivos accidentales, temporales, dependencias generadas, builds o logs entre los cambios. El escaneo de patrones y la comparación privada con los secretos locales no encontraron credenciales reales, tokens reales ni claves privadas. Los valores de pruebas son fixtures sintéticos de bases efímeras. `.env`, variantes locales, claves PEM/KEY, logs, node_modules, target, dist y herramientas locales siguen ignorados; no hay archivos sensibles o generados rastreados. `git diff --check` y la revisión de espacios finales de archivos nuevos no reportaron errores.

V1–V4 no tienen diferencias respecto de HEAD; V5 solo contiene payments, refunds y la extensión del recibo de idempotencia. No se alteraron configuración de seguridad de identidad, dependencias ni Compose. Los cambios en reservas son el acceso compartido, bloqueo común, idempotencia tipada y refund automático de cancelación. No hay operaciones de recepción ni funcionalidades de Fase 7.

HEAD, main y la referencia local origin/main siguen en `689b0682f3377bf2d2119cf82fad8455faab57a1`; upstream origin/main y diferencia 0/0. El working tree contiene intencionalmente los 36 cambios sin staging. Staging vacío; no se creó ningún commit ni se realizó push. Esta comprobación compara las referencias locales, sin efectuar una nueva consulta al remoto.

## Límites pendientes

- No se mueve dinero real ni existe integración externa.
- Solo pago completo y refund completo automático; ningún refund manual o parcial.
- Recibos vencidos se conservan, manteniendo la política de Fase 5; la retención/archivo a largo plazo sigue pendiente.
- Reemplazar el simulador por un proveedor real requiere diseñar sus efectos externos y recuperación; no se anticipa ese trabajo aquí.
- isFullyPaid es una precondición interna preparada. No existen endpoints ni UI de check-in, check-out o no-show, ni se cambia la reserva a esos estados.
- No hay CI remota de estos cambios locales; staging, commit, push y Fase 7 requieren autorización posterior.
