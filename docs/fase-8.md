# Fase 8 — Administración

Entrega local para revisión sobre `1bd3bb2019c665e0d0d8902f18df2740898b4993` (Fase 7 aprobada). La inspección inicial confirmó HEAD/main/origin/main iguales, diferencia 0/0, working tree limpio y staging vacío. Se implementa exclusivamente administración; no se autoriza staging, commit, push ni Fase 9.

## Usuarios y permisos

ADMIN puede buscar/listar, consultar, crear, cambiar rol y activar/desactivar usuarios. Los roles continúan siendo CLIENTE, EMPLEADO y ADMIN; no se permite crear roles arbitrarios. No existe DELETE de usuarios ni borrado de reservas, pagos, auditoría o sesiones históricas.

| Ruta bajo /api/v1 | Método | Respuesta |
|---|---|---|
| /users | GET | PageView de usuarios administrativos |
| /users/{id} | GET | AdminUserView; sustituye la implementación administrativa mínima de Fase 2 sin duplicar ruta |
| /users | POST | 201, Location y AdminUserView |
| /users/{id}/role | PATCH | Usuario actualizado; cuerpo role + version |
| /users/{id}/status | PATCH | Usuario actualizado; cuerpo active + version |
| /admin/dashboard | GET | Métricas por periodo obligatorio |
| /admin/audit-events | GET | PageView de eventos seguros |

Todos requieren ADMIN en backend. CLIENTE/EMPLEADO reciben 403 con peticiones válidas; anónimo o sesión inválida, 401. Las rutas /users/me, contraseña y sesiones propias conservan su contrato. No se modifica SecurityConfiguration, el conversor JWT/sesión ni la política CSRF/Origin. Como antes, la validación de un DTO inválido puede devolver 400 antes del control de rol del método.

AdminUserView contiene exclusivamente id, name, email, role, active, version, createdAt y updatedAt. No incluye passwordHash, securityVersion, JWT ni sesiones. El POST recibe name, email, password y role; activo inicialmente, UUID/versiones/fechas generados por servidor. Se conservan las validaciones de identidad (nombre no vacío, máximo 100; email válido, máximo 254; contraseña mínimo 12 caracteres y máximo 72 bytes UTF-8), normalización de email y PasswordEncoder BCrypt existente. AuthService.requirePassword pasa a ser reutilizable; su comportamiento no cambia. No hay contraseña administrativa predeterminada.

GET users admite q literal por nombre/email, role, active, page (0–100000), size (1–100, 20 por defecto), sort=name|email|active|createdAt|id,asc|desc. Por defecto name,asc, con id como desempate. Filtros, conteo, orden y paginación se ejecutan en PostgreSQL; no se filtran páginas en memoria. No se permite ordenar por rutas internas como passwordHash.

Errores reutilizados: USER_NOT_FOUND (404), EMAIL_UNAVAILABLE (409; también la colisión de email único de BD), INVALID_REQUEST para roles/campos desconocidos (400), PASSWORD_POLICY (400), LAST_ACTIVE_ADMIN y USER_VERSION_CONFLICT (409). Se preservan Problem Details y requestId sin SQL, cuerpos ni stack traces.

## Último ADMIN y seguridad inmediata

Todas las mutaciones administrativas toman primero `SELECT id FROM roles WHERE name='ADMIN' FOR NO KEY UPDATE`. Esta fila existente serializa la comprobación del número de administradores y el cambio hasta commit/rollback. FOR NO KEY UPDATE es compatible con los KEY SHARE de las FK; no se añade una tabla ni un mutex Java. Después se bloquean actor y destinatario por UUID ordenado, antes de cargar estado mutable. El orden global es rol ADMIN → usuarios → sesiones, compatible con identidad (usuario → sesiones).

Después de esperar los locks se valida nuevamente que el actor siga activo, con rol ADMIN, securityVersion coherente con el JWT y sesión vigente. Así, una petición administrativa en espera no conserva privilegios perdidos mientras esperaba. Antes de degradar o desactivar un ADMIN activo se cuenta el conjunto activo bajo el mismo lock: si solo queda uno, LAST_ACTIVE_ADMIN. Activar/promover también sigue el protocolo.

La garantía cubre las rutas de aplicación y el aprovisionamiento incluido; una escritura SQL privilegiada ajena al protocolo puede violar políticas de aplicación. No se atribuye a un CHECK de BD una garantía que no implementa.

Cada cambio real de rol/estado incrementa securityVersion y, mediante @Version, la versión de edición. Revoca todas las sesiones del destinatario sin eliminarlas. La siguiente petición con JWT antiguo y cualquier refresh revocado son rechazados. Reactivar exige iniciar una sesión nueva; no resucita sesiones anteriores. Contraseñas, issuer, audience, expiración, rotación, reuso, cookies, rate limits y BCrypt conservan la política anterior.

Se permite auto-degradación/auto-desactivación únicamente si queda otro ADMIN activo. La respuesta de esa mutación puede ser 200, pero las peticiones posteriores quedan invalidadas; React descarta inmediatamente su sesión y caché. Enviar una versión obsoleta devuelve USER_VERSION_CONFLICT. Enviar el mismo rol/estado con versión vigente es no-op: no modifica versión, sesiones ni auditoría.

## Auditoría y V6

V2 contenía metadatos de auditoría, pero faltaba el campo JSONB previsto por el plan. **V6__administrative_audit_changes.sql es necesaria únicamente para añadir changes JSONB NOT NULL DEFAULT '{}' con CHECK de objeto.** Los eventos históricos conservan sus metadatos y reciben objeto vacío. V1–V5 permanecen intactas. No se añaden índices especulativos: ya existen índices de email, auditoría por fecha/actor, claves primarias y los de negocio; búsquedas de subcadena y agregados deben evaluarse con volumen real antes de optimizar.

Se reutiliza AuditService. Las nuevas acciones USER_CREATED, USER_ROLE_CHANGED, USER_ACTIVATED y USER_DEACTIVATED registran actor, USER, id, requestId, fecha y solo oldRole/newRole/oldActive/newActive según corresponda. Un fallo de auditoría revierte también el usuario, sus versiones y la revocación de sesiones. Se conservan las llamadas antiguas a record sin JSON.

AuditChanges utiliza una lista cerrada tanto para escritura como lectura: roles escalares de los tres valores permitidos y booleanos de actividad. No se serializa JSON histórico arbitrario ni objetos anidados aunque llegaran a existir en la base. No se guardan contraseñas, hashes, cookies, tokens, nombres o correos en los nuevos cambios JSON.

GET audit-events permite actorId, action, resource, resourceId, requestId exactos y from/to como instantes ISO. Los límites son [from,to); pueden usarse unilateralmente y, si ambos existen, from < to. Paginación común page/size, sort=occurredAt|action|resource|id,asc|desc y desempate UUID; predeterminado occurredAt,asc. Todos los filtros y el orden se aplican mediante SQL parametrizado y columnas de orden permitidas. El DTO incluye metadatos y únicamente cambios saneados. La UI envía fechas/horas UTC expresamente rotuladas.

## Dashboard: fórmulas exactas

GET /admin/dashboard exige from y to como fechas ISO del hotel. Intervalo **[from,to)** de 1 a 366 noches; nunca se usa la zona accidental del servidor. Los límites de timestamps son from.atStartOfDay(hotelZone) y to.atStartOfDay(hotelZone), respetando cambios horarios. Se usa hotel.time-zone, predeterminado America/Lima. Un único statement SQL calcula las métricas sobre el mismo snapshot de PostgreSQL.

| Campo | Fórmula y fuente |
|---|---|
| reservationsCreated | COUNT de reservations.created_at dentro de los límites de instantes; todos los estados |
| arrivals | COUNT de reservas con fecha programada check_in en [from,to) y estado CONFIRMED/CHECKED_IN/CHECKED_OUT |
| departures | COUNT equivalente por check_out programado; mismos estados |
| eligibleRooms | COUNT de rooms actualmente activas, operational_status=ACTIVE, con room_type activo |
| roomNightsOccupied | Suma de min(check_out,to) − max(check_in,from) para reservas bloqueantes que intersectan el periodo y pertenecen al mismo conjunto eligibleRooms |
| roomNightsAvailable | eligibleRooms × número de noches del periodo |
| occupancyPercent | roomNightsOccupied / roomNightsAvailable × 100, dos decimales HALF_UP; null si denominador cero |
| approvedPayments | SUM NUMERIC de payments.amount APPROVED cuyo created_at cae en el periodo |
| refunds | SUM NUMERIC de refunds.amount cuyo propio created_at cae en el periodo |
| netRevenue | approvedPayments − refunds, BigDecimal, con moneda explícita |

La ocupación es **reservada sobre el inventario actualmente operativo**, no presencia física ni reconstrucción del inventario histórico. Incluye CONFIRMED impagadas y CHECKED_OUT con su rango original; excluye CANCELLED/NO_SHOW, habitaciones inactivas/en mantenimiento/fuera de servicio y tipos inactivos del numerador y denominador. Un cambio posterior de inventario puede cambiar un informe de un periodo pasado: no existe historial de inventario suficiente para afirmar otra cosa. Llegadas/salidas son programadas, no timestamps reales de recepción.

No se une Payment con Refund para sumar, evitando multiplicación de filas. DECLINED nunca suma. Replays no crean filas nuevas, y unicidades existentes impiden dobles aprobados/refunds. El periodo de cada movimiento es independiente: un refund en el periodo de un pago anterior puede producir neto negativo. Las sumas vacías devuelven 0.00. Se utiliza hotel.currency (PEN por defecto); si hay movimientos de otra moneda en el periodo se rechaza con 409 METRIC_CURRENCY_MISMATCH, en lugar de mezclarlos o descartarlos silenciosamente.

## Frontend

Rutas /admin/users, /admin/users/new, /admin/users/:id, /admin/dashboard y /admin/audit-events. AdminLayout comprueba recuperación de sesión/rol y no monta consultas privadas para CLIENTE/EMPLEADO. La navegación ADMIN integra Usuarios, Panel y Auditoría. La autorización efectiva sigue estando en backend.

Usuarios: filtros, páginas, detalle, creación con React Hook Form/Zod y política de contraseña reutilizada, selección explícita de rol y confirmación para cambios sensibles. Solo se envían campos permitidos. En 409 o error no se sobrescribe automáticamente; se ofrece actualizar. Auto-modificación que altera permisos/actividad llama clearSession y vacía caché. Ninguna credencial nueva se persiste en almacenamiento web.

Panel: periodo explícito, tarjetas con unidades, zona/moneda, fórmulas visibles, denominador cero y neto de refunds. No añade librerías de gráficas. Auditoría: filtros SQL, paginación, detalle seguro de campos permitidos, actor opcional y requestId. Las pantallas manejan carga, vacío, error y falta de permiso.

## Primer administrador y smoke local

No hay cuenta privilegiada automática. Para aprovisionar el primer ADMIN local: registrar primero una cuenta con contraseña individual y ejecutar explícitamente, desde la raíz, `node scripts/bootstrap-admin.mjs <correo-del-usuario-activo>`. Requiere acceso al PostgreSQL de Compose y esquema V6. Solo funciona si no existe ningún ADMIN activo, utiliza el mismo lock de rol, incrementa versiones, revoca sesiones y audita USER_ROLE_CHANGED con actor null (operación del operador local). No cambia la contraseña. Después se inicia sesión de nuevo. Este script no es un endpoint y no se ejecuta automáticamente al arrancar.

`node scripts/administration-smoke.mjs` usa Compose local en puerto 3000 y requiere cero ADMIN activos para probar la protección del primero sin alterar cuentas existentes. Registra un ADMIN sintético mediante el procedimiento de bootstrap, comprueba su rechazo posterior, crea un cliente mediante API y recorre rol, desactivación/activación, revocación, último ADMIN, dashboard de periodo fijo, refund y auditoría. Limpia exclusivamente sus UUID/correos sintéticos. No se usa el bootstrap sobre la cuenta real preexistente. El recorrido E2E administrativo es HTTP; Playwright de presentación sigue fuera de esta fase.

## Pruebas y resultados

AdministrationIT usa servidor HTTP real y PostgreSQL efímero. Cubre roles, DTO seguro, filtros/páginas, validación, creación por cada rol, duplicados, sesiones, auto-modificación, último ADMIN, edición simultánea, no-op, rollback de creación/rol/estado, métricas exactas, límites, denominador cero, neto negativo, moneda distinta, auditoría histórica y saneamiento de JSON.

Las cuatro carreras críticas (rol/rol, estado/estado y ambos órdenes mixtos) usan dos clientes HTTP y transacciones/conexiones independientes. Un trigger de prueba pausa a la primera en auditoría mientras conserva locks; pg_stat_activity confirma la espera de la segunda. Se exige 200/409, LAST_ACTIVE_ADMIN, un único evento y exactamente un ADMIN activo final. No son llamadas secuenciales. Los triggers existen solamente durante cada prueba efímera.

La migración se comprueba desde cero y mediante upgrade V5 → V6 con un evento histórico conservado. Las suites históricas actualizan expectativas de fase/esquema/rutas; la prueba de upgrade V4 → V5 fija ahora target=5 para conservar su alcance original. FoundationIT permite 30 segundos únicamente para la generación inicial de OpenAPI (antes 10); las otras llamadas mantienen 10 segundos. No es una prueba de rendimiento ni se modifica el timeout de la aplicación.

| Verificación | Resultado |
|---|---|
| Backend administrativo | 25 pruebas, 0 fallos, 0 errores, 0 omitidas; Maven verify BUILD SUCCESS |
| Regresiones backend Fases 2–7 | 175 pruebas verificadas por grupos: Authentication 17, Availability 37, Catalog 18, Foundation 4, Payment 25, Reception 43, Reservation 31; total backend con administración: 200 casos únicos aprobados |
| Frontend completo | 128 pruebas aprobadas: 109 de regresión y 19 administrativas; 10 archivos |
| Builds y lint | TypeScript/Vite y ESLint correctos; Maven verify compila, empaqueta el ejecutable y termina BUILD SUCCESS en el cierre focalizado |
| Flyway / Docker / OpenAPI | V1–V6 desde cero y upgrade V5 → V6 correctos; imágenes reconstruidas; PostgreSQL/backend/frontend healthy; OpenAPI 0.8.0 y Swagger responden, esquemas de usuarios/reservas independientes |
| Smoke HTTP | Técnico, administración completa, reservas/pagos y recepción aprobados sobre Compose reconstruido; fixtures eliminados |
| Secretos / alcance / Git | Sin secretos reales ni artefactos en los 35 cambios; .env y generados ignorados; V1–V5 intactas; staging vacío; main/origin/main y remoto real conservan 1bd3bb2, 0/0; Fase 9 no iniciada |

La primera suite administrativa terminó con 17 casos aprobados y 3 errores de fixture: el código de habitación debía estar normalizado en mayúsculas. Se corrigió el dato sintético, preservando la restricción del catálogo.

La revisión del OpenAPI generado detectó una colisión entre los DTO anidados Create de usuarios y reservas. Se asignó el nombre de esquema explícito AdminUserCreate únicamente al DTO nuevo; no se alteró el contrato HTTP de reservas. La prueba de OpenAPI y el smoke verifican que usuarios documente credenciales de entrada writeOnly/rol, y reservas conserve roomId/fechas sin password.

La regresión de 175 casos terminó con 174 aprobados y un timeout en FoundationIT al generar OpenAPI por primera vez. Tras ampliar exclusivamente esa espera a 30 segundos se ejecutaron los 4 casos FoundationIT y el caso ampliado de OpenAPI/migración de AdministrationIT: 5 aprobados, sin fallos, errores ni omitidos, BUILD SUCCESS. Las otras 171 pruebas históricas y 24 administrativas no se repitieron: la anotación de nombre OpenAPI y la espera de prueba no alteran sus comportamientos. Los 200 casos únicos quedaron verificados, sin presentar la primera ejecución con timeout como exitosa. Los XML de Failsafe se sobrescriben por clase al repetir selectivamente: el último AdministrationIT contiene solo el caso repetido, no reemplaza la evidencia anterior de sus 25 casos.

En Windows, la ejecución frontend con threads aprobó las 109 pruebas anteriores, pero agotó el tiempo de arranque del worker administrativo. Se ejecutó únicamente ese archivo con forks y un worker: sus 19 pruebas pasaron sin errores. El build y lint se ejecutaron nuevamente incluyendo el archivo nuevo. No se presenta la primera ejecución con error de infraestructura como una suite completa exitosa.

Comandos de pruebas de esta entrega:

```powershell
docker compose --profile test run --rm backend-tests '-Dit.test=AdministrationIT'
docker compose --profile test run --rm backend-tests '-Dit.test=AuthenticationIT,CatalogIT,AvailabilityIT,ReservationIT,PaymentIT,ReceptionIT,FoundationIT'
docker compose --profile test run --rm backend-tests '-Dit.test=FoundationIT,AdministrationIT#upgradeV5KeepsHistoricalAuditAndOpenApiDocumentsAdminContracts'
# En frontend:
npm.cmd test -- --pool=threads --maxWorkers=1
npm.cmd test -- src/features/administration/Administration.test.tsx --pool=forks --maxWorkers=1
npm.cmd run build
npm.cmd run lint
```

Verificación integrada final del 11 de septiembre de 2026:

```powershell
docker compose config --quiet
docker compose up --build --wait --wait-timeout 240
node scripts/smoke.mjs
node scripts/administration-smoke.mjs
node scripts/reservations-smoke.mjs --payments
node scripts/reception-smoke.mjs
docker compose ps
```

Todos terminaron con exit code 0. El empaquetado Maven de la imagen terminó BUILD SUCCESS; la construcción TypeScript/Vite de la imagen también pasó. Compose confirmó los tres servicios healthy. Flyway validó seis migraciones y actualizó la base persistente de V5 a V6; PostgreSQL confirmó versiones 1–6 exitosas y changes de tipo jsonb, NOT NULL, default '{}'. No se recreó ni borró el volumen de desarrollo.

El smoke administrativo comprobó registro sintético/aprovisionamiento del primer ADMIN, rechazo de un segundo bootstrap, login, creación/detalle/listado filtrado y paginado, cambios de rol/estado, 409 por versión obsoleta, JWT revocado, 403 del empleado, último ADMIN, métricas y correlación por requestId. En [2040-10-10,2040-10-13), America/Lima: 1 reserva creada, 1 llegada, 1 salida, 2/3 noches de habitación, ocupación 66.67%, APPROVED PEN 200.20, refund inicial 0 y neto 200.20; después del refund PEN 200.20, neto 0.00. Las rutas SPA y los esquemas OpenAPI se comprobaron sobre el frontend servido por Nginx.

Los otros smokes comprobaron reservas/replays, exclusión de disponibilidad, historial/detalle, DECLINED/APPROVED, doble pago bloqueado, cancelación con un refund, check-in, check-out anticipado y no-show. El conteo final de PostgreSQL volvió a 1 usuario preexistente, 0 ADMIN activos, 0 habitaciones, 0 reservas, 0 pagos y 0 refunds. No se aprovisionó una cuenta real ni se dejaron cuentas demo. El backend reconstruido no registró entradas ERROR durante estas verificaciones.

Los compiladores informan uso de API Jackson deprecada en auditoría/pruebas y Mockito avisa sobre futura política de agentes de Java; son avisos, no fallos de compilación/pruebas. La regresión también genera errores sintéticos de auditoría y cierres de conexiones de bases efímeras como parte de sus casos de rollback/ciclo de vida; no se confunden con errores del runtime final.

## Archivos de la entrega

35 archivos locales: 17 modificados y 18 nuevos, todos sin staging.

| Categoría | Archivos |
|---|---|
| Backend nuevo (8) | users/api/AdminUserController.java, AdminUserRequests.java, AdminUserView.java; users/application/AdminUserService.java; audit/AuditChanges.java, AuditQueryService.java; reporting/AdminReportingController.java, DashboardService.java |
| Backend existente (8) | users/domain/User.java, users/infrastructure/UserRepository.java, users/api/UserController.java; identity/application/AuthService.java; audit/AuditService.java; shared/api/ApiErrors.java, SystemController.java; shared/config/OpenApiConfiguration.java |
| Migración nueva (1) | V6__administrative_audit_changes.sql |
| Pruebas backend (5) | AdministrationIT.java nuevo; AvailabilityIT.java, FoundationIT.java, PaymentIT.java y ReceptionIT.java ajustados a fase/esquema/contratos actuales y espera de generación OpenAPI |
| Frontend (6) | app/App.tsx modificado; features/administration/AdminLayout.tsx, UsersPage.tsx, ReportingPages.tsx, api.ts y Administration.test.tsx nuevos |
| Scripts (3) | bootstrap-admin.mjs y administration-smoke.mjs nuevos; smoke.mjs actualizado a fase 8 |
| Documentación (4) | fase-8.md nuevo; README.md, docs/plan-inicial.md y docs/auth-api.md actualizados |

## Límites y revisión

No se implementan Fase 9, demo final, despliegue productivo, correo, recuperación de contraseñas, pagos reales, múltiples hoteles/habitaciones ni cambios de fechas. No se modifica historial físicamente mediante API. Las métricas usan inventario actual y paginación por offset; se documentan sus límites. Configuración HTTPS, retención y presentación final mantienen su alcance posterior.

La revisión final no encontró cambios de dependencias, configuración Docker/CI, SecurityConfiguration, conversor JWT/sesiones, migraciones V1–V5 ni reglas de catálogo/disponibilidad/reservas/pagos/recepción. Las modificaciones de identidad se limitan a reutilizar la política de contraseña, ampliar el DTO administrativo de GET /users/{id}, capacidades del modelo/repositorio de usuarios y mapear la colisión de email al código existente.

Se revisaron los archivos cambiados contra los secretos locales sin imprimirlos y contra patrones de JWT, claves privadas y credenciales; solo existen fixtures sintéticos de prueba. .env, variantes sensibles, claves, logs, node_modules, target, dist, coverage y reportes generados permanecen ignorados. git diff --check no informa errores; los archivos nuevos tampoco contienen espacios finales ni marcadores de conflicto.

Estado final: HEAD = main = origin/main = remoto real `1bd3bb2019c665e0d0d8902f18df2740898b4993`; ahead/behind 0/0. Working tree con 35 cambios de esta entrega (17 modificados, 18 nuevos); índice vacío. La entrega se conserva sin staging, commit ni push, pendiente de revisión del usuario. Fase 9 no fue iniciada.
