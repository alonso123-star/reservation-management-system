# Reservation Management System

Sistema Full Stack de reservas de hotel, construido como un monolito modular.

**Estado:** Fases 0–7 aprobadas y sincronizadas con GitHub en `1bd3bb2`. Fase 8 — Administración implementada localmente para revisión: usuarios, roles, activación/desactivación, último ADMIN protegido bajo concurrencia, panel y consulta de auditoría. Véase [docs/fase-8.md](docs/fase-8.md). Sin staging, commit ni push de esta entrega. Fase 9 no iniciada.

## Incluido en esta base

- React + TypeScript con Vite y una pantalla que consulta la disponibilidad real del entorno.
- Spring Boot, Java 21, Spring Data JPA y Actuator.
- PostgreSQL 18 y una migración Flyway que prepara la extensión `btree_gist`.
- OpenAPI/Swagger para el endpoint técnico de información.
- Docker Compose con comprobaciones de salud, volumen persistente y proxy Nginx.
- Pruebas JUnit con PostgreSQL mediante Testcontainers y pruebas de interfaz con Vitest.
- CI de frontend, backend y arranque completo con Compose.
- Git, Maven Wrapper y lockfile de npm.
- Registro de clientes, login, JWT, refresh con rotación y revocación, logout y cambio de contraseña.
- Roles CLIENTE, EMPLEADO y ADMIN; permisos por rol y propiedad de sesión en el backend.
- Pantallas de registro, acceso, perfil y sesiones activas con React Router, TanStack Query, React Hook Form y Zod.
- Spring Security, CSRF, control de origen, límites de intentos y auditoría de identidad.
- Catálogo público de tipos y habitaciones, inventario ADMIN/EMPLEADO, filtros y paginación en PostgreSQL.
- Formularios de catálogo, activación/desactivación, estados operativos, control optimista de edición y auditoría.
- Búsqueda pública por estancia y huéspedes, tipo, precio y paginación, que excluye reservas bloqueantes y calcula el total estimado. Consultar no bloquea habitaciones; crear la reserva vuelve a comprobar elegibilidad dentro de una transacción.
- Reservas propias de CLIENTE y para clientes por EMPLEADO/ADMIN, precio histórico, historial, detalle, cancelación y auditoría atómica.
- Idempotency-Key con recibo persistente y restricción PostgreSQL GiST que impide reservas solapadas incluso entre transacciones independientes.
- Pagos simulados deterministas, historial de intentos/reembolsos y cancelación pagada atómica; PostgreSQL impide dos APPROVED por reserva o dos refunds por pago.
- Recepción para EMPLEADO/ADMIN: llegadas, huéspedes alojados, check-in, check-out y no-show con política horaria del hotel, versión, bloqueo y auditoría atómica.
- Administración exclusiva de ADMIN: usuarios, roles, estado, sesiones revocadas al cambiar permisos, métricas por periodo y auditoría filtrada/paginada.

## Versiones

| Componente | Versión |
|---|---|
| Java | 21 LTS |
| Spring Boot | 4.1.1 |
| Maven Wrapper / Maven | 3.3.4 / 3.9.16 |
| PostgreSQL | 18.6 |
| springdoc | 3.1.1 |
| Node.js usado en Docker y CI | 24.12.0 |
| React | 19.2.8 |
| TypeScript | 5.9.3 |
| Vite | 8.2.2 |
| Vitest | 5.0.0 |
| ESLint | 10.10.0 |

Las dependencias transitivas Java se gestionan mediante Spring Boot; las de frontend quedan registradas en `frontend/package-lock.json`. Las imágenes de Java reciben actualizaciones dentro de Java 21. Los tags de imágenes pueden actualizarse en origen; no constituyen una fijación por digest.

## Arranque con Docker

Requisitos: Docker Desktop con motor Linux activo, Docker Compose y Node.js para generar la configuración local. Esta ruta **no requiere Java ni Maven instalados en el equipo**.

Desde la raíz del proyecto, en PowerShell:

```powershell
node scripts/setup-env.mjs
```

El script crea `.env` o completa las claves que faltan, genera una contraseña PostgreSQL y una clave JWT aleatorias y conserva los valores ya configurados. No imprime secretos. `.env` está excluido de Git. También puedes copiar manualmente la plantilla y configurar ambos valores: la clave JWT debe ser Base64 de al menos 32 bytes aleatorios.

```powershell
docker compose config --quiet
docker compose up --build --wait --wait-timeout 240
```

La primera construcción descarga imágenes y dependencias; puede tardar varios minutos.

| Servicio | Dirección predeterminada |
|---|---|
| Aplicación | http://localhost:3000 |
| Búsqueda de estancia | http://localhost:3000/availability |
| Historial autenticado | http://localhost:3000/reservations |
| Recepción del personal | http://localhost:3000/staff/reception |
| Usuarios / panel / auditoría ADMIN | http://localhost:3000/admin/users · /admin/dashboard · /admin/audit-events |
| API técnica | http://localhost:3000/api/v1/system/info |
| Disponibilidad de backend y PostgreSQL | http://localhost:3000/api/v1/system/health/readiness |
| Swagger UI | http://localhost:3000/swagger-ui/index.html |
| OpenAPI | http://localhost:3000/v3/api-docs |
| Backend directo | http://localhost:8080 |
| PostgreSQL local | localhost:5432 |

Los puertos publicados se limitan a `127.0.0.1`. Este Compose corresponde a desarrollo local; no es una publicación de producción.

```powershell
docker compose ps
docker compose logs --tail 100 backend
docker compose down
```

`down` conserva el volumen de PostgreSQL. No uses `down --volumes` salvo que quieras borrar explícitamente los datos locales. Cambiar la contraseña en `.env` después de inicializar el volumen no cambia automáticamente la contraseña existente en PostgreSQL.

## Comprobaciones

Frontend, con Node.js 24.12.0:

```powershell
cd frontend
npm.cmd ci
npm.cmd run lint
npm.cmd test
npm.cmd run build
cd ..
```

Backend con Java 21 dentro de Docker, sin depender del Java local:

```powershell
docker compose --profile test run --rm backend-tests
```

Este servicio usa Testcontainers para crear una base de datos efímera independiente de la base de desarrollo. Necesita acceso al motor Docker mediante su socket. No utiliza ni limpia el volumen `postgres_data`.

Backend si ya tienes un JDK 21 y Docker activo:

```powershell
cd backend
.\mvnw.cmd -B -ntp verify
cd ..
```

`verify` ejecuta las pruebas de integración con Maven Failsafe; `test` por sí solo no ejecuta las clases `*IT`. Fase 8 añade 25 pruebas backend a las 175 existentes y 19 frontend a las 109 anteriores. En Windows, si los procesos de Vitest agotan el tiempo de inicio, utiliza `npm.cmd test -- --pool=forks --maxWorkers=1`; ejecuta la misma suite con un worker. Los resultados y cualquier ejecución por grupos se registran en `docs/fase-8.md`.

Prueba del conjunto ya arrancado, desde la raíz y con Node.js:

```powershell
node scripts/smoke.mjs
```

Si cambias `FRONTEND_PORT`, indica la URL correspondiente en `SMOKE_BASE_URL`.

Para comprobar también reserva, pago rechazado/aprobado, replays, prevención de doble pago, cancelación con reembolso y disponibilidad en el Compose local de puerto 3000:

```powershell
node scripts/reservations-smoke.mjs --payments
```

Este segundo script requiere Docker y `.env` local. Crea datos sintéticos identificados por UUID, una cuenta temporal, reserva e intentos de pago; elimina únicamente sus propios datos al terminar. Sin `--payments` conserva el recorrido de reserva sin pago. No imprime credenciales ni tokens. No está destinado a producción.

Para comprobar recepción, permisos, pago completo, salida anticipada y no-show con disponibilidad:

```powershell
node scripts/reception-smoke.mjs
```

También requiere Compose local en puerto 3000 y `.env`. Crea catálogo y cuentas sintéticas, habilita EMPLEADO únicamente en su propia cuenta temporal y elimina exclusivamente sus fixtures. Las fechas siguen el día del hotel; el recorrido determinista con avance controlado del reloj se ejecuta en ReceptionIT.

Para comprobar administración de usuarios, revocación, último ADMIN, métricas exactas y auditoría:

```powershell
node scripts/administration-smoke.mjs
```

Requiere Compose local y cero ADMIN activos: comprueba el primer aprovisionamiento con una cuenta sintética y limpia exclusivamente sus fixtures. Si ya existe un ADMIN, se detiene antes de crear datos; las pruebas aisladas de AdministrationIT no tienen esa limitación.

## Desarrollo con recarga del frontend

Con el backend y PostgreSQL funcionando en Compose:

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

Abre http://localhost:5173. Vite reenvía las peticiones al backend en http://localhost:8080. Si cambias su puerto, adapta el destino en `frontend/vite.config.ts`.

Para desarrollar Java fuera de Docker necesitarás JDK 21. Detén primero el backend de Compose para liberar el puerto:

```powershell
docker compose stop backend frontend
docker compose up -d db
$databaseConfig = Get-Content .env -Raw | ConvertFrom-StringData
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($databaseConfig.POSTGRES_PORT)/$($databaseConfig.POSTGRES_DB)"
$env:SPRING_DATASOURCE_USERNAME = $databaseConfig.POSTGRES_USER
$env:SPRING_DATASOURCE_PASSWORD = $databaseConfig.POSTGRES_PASSWORD
$env:JWT_SECRET_BASE64 = $databaseConfig.JWT_SECRET_BASE64
$env:AUTH_COOKIE_SECURE = "false"
cd backend
.\mvnw.cmd spring-boot:run
```

Este ejemplo requiere que `.env` mantenga el formato simple `CLAVE=valor` de la plantilla.

## Estructura actual

- `backend/`: módulos identity, users, rooms, reservations, payments, reception, audit, reporting y shared, migraciones y pruebas de integración.
- `frontend/`: aplicación React, catálogo, disponibilidad, reservas, pagos simulados, recepción, administración, inventario del personal, identidad, cliente HTTP y pruebas.
- `infrastructure/nginx/`: archivos estáticos y proxy al backend.
- `scripts/smoke.mjs`: comprobación HTTP del conjunto.
- `.github/workflows/ci.yml`: verificación automática al hacer push o abrir un pull request.
- `docs/plan-inicial.md`: planificación aprobada.
- `docs/adr/0001-base-ejecutable.md`: decisiones de la Fase 1.
- `docs/fase-1.md`: alcance y registro de verificación.
- `docs/auth-api.md`: contrato de identidad, cookies, CSRF y errores.
- `docs/adr/0002-identidad-autenticacion.md`: decisiones de seguridad y concurrencia.
- `docs/fase-2.md`: alcance y comprobaciones de identidad.
- `docs/catalog-api.md`: rutas, filtros, permisos y concurrencia del catálogo.
- `docs/adr/0003-catalogo.md`: decisiones de catálogo y límites del alcance.
- `docs/fase-3.md`: informe y resultados de verificación de catálogo.
- `docs/fase-4.md`: informe histórico de la entrega de disponibilidad.
- `docs/fase-5.md`: modelo, contrato, concurrencia, idempotencia y verificaciones de reservas.
- `docs/fase-6.md`: informe histórico del simulador, pagos/refunds, locking, contratos y verificaciones.
- `docs/fase-7.md`: informe histórico de recepción, política temporal, transiciones y concurrencia.
- `docs/fase-8.md`: usuarios administrativos, auditoría, fórmulas del panel, contratos y verificaciones de esta entrega.

Los módulos de negocio se crearán cuando comience su fase, evitando carpetas vacías y código anticipado.

## Configuración y límites

Flyway administra el esquema y Hibernate utiliza `ddl-auto=validate`. V1 instala `btree_gist`; V2 añade identidad y auditoría; V3 añade catálogo; V4 añade reservas e idempotencia. V5 incorpora payments/refunds y extiende los recibos para pagos. Recepción reutiliza V4 sin migración. Administración añade V6 exclusivamente para el JSONB de cambios permitidos de auditoría que faltaba en V2. V1–V5 permanecen intactas. Nunca se usa `ddl-auto=update` ni se reescriben migraciones históricas.

La comprobación de readiness incluye PostgreSQL. Un backend vivo sin acceso a su base no se considera listo. El endpoint no devuelve detalles internos.

Swagger está habilitado para desarrollo. `API_DOCS_ENABLED=false` permite desactivarlo al configurar el backend. Solo se expone el endpoint Actuator de salud.

Para probar la aplicación, crea una cuenta desde **Crear cuenta** y después inicia sesión. No hay usuarios privilegiados predeterminados. El registro siempre asigna CLIENTE. Para aprovisionar expresamente el primer ADMIN local, registra una cuenta y ejecuta `node scripts/bootstrap-admin.mjs <correo>` con acceso al Compose local. Solo funciona si no existe un ADMIN activo, conserva la contraseña, revoca sesiones y audita; después debes iniciar sesión nuevamente. Los siguientes usuarios/roles se administran desde la interfaz ADMIN.

El JWT de acceso dura 15 minutos y se conserva solo en memoria. El refresh dura 7 días absolutos, rota y viaja en cookie HttpOnly/SameSite Strict. Reutilizar un refresh consumido revoca su familia. Logout invalida la sesión y cambiar contraseña invalida todas. La API comprueba usuario, rol, versión y sesión en PostgreSQL en cada petición autenticada.

Origin es obligatorio para todas las mutaciones, incluso desde herramientas HTTP. CSRF protege operaciones con cookies; Spring Resource Server exceptúa peticiones Bearer explícitas. React también envía CSRF con esas peticiones. Consulta los contratos de identidad y catálogo para la secuencia. Compose utiliza cookies sin Secure exclusivamente por su HTTP local; para HTTPS se debe usar Secure=true y configurar los orígenes autorizados. El backend por defecto exige Secure.

Las credenciales de PostgreSQL son locales; su usuario administra la base para permitir las migraciones. Para despliegue quedan por definir permisos separados de migración, proxies confiables para límites por IP y retención/purga de sesiones y auditoría. El ADR detalla estos límites.

Catálogo público: `/catalog/types` y `/catalog/rooms`. Inventario: `/staff/catalog/types` y `/staff/catalog/rooms`. `ACTIVE` es estado operativo; no indica disponibilidad para fechas. La tarifa base usa PEN por defecto; cada reserva guarda tarifa, moneda e importe históricos. La cancelación usa `hotel.time-zone`, por defecto `America/Lima`.

Una reserva se confirma inicialmente sin pago. Desde su detalle puede iniciarse el simulador: primer intento nuevo DECLINED y siguiente APPROVED; reintentar la misma clave conserva el resultado. No se solicitan datos de tarjeta ni se mueve dinero real. El backend usa el total y moneda históricos, y cancelar una reserva pagada genera un refund completo automáticamente.

Recepción requiere EMPLEADO/ADMIN. Check-in exige pago completo vigente y fecha local dentro de la estancia. No-show se permite estrictamente después de `HOTEL_ARRIVAL_DEADLINE` (22:00 del día de llegada por defecto), en `HOTEL_TIME_ZONE` (America/Lima); libera disponibilidad sin reembolso automático. Check-out conserva fechas, precio y bloqueo del rango original incluso si es anticipado. No hay automatismos de no-show.

Administración requiere ADMIN y protege el último administrador activo mediante bloqueo PostgreSQL. Cambiar rol o estado revoca sesiones inmediatamente. El panel usa [from,to) en la zona del hotel: ocupación reservada sobre inventario actualmente operativo, llegadas/salidas programadas y pagos APPROVED menos refunds por la fecha de cada movimiento. Las fórmulas y sus límites están en `docs/fase-8.md`.

La CI está configurada en GitHub. Las comprobaciones de Fase 8 son locales hasta autorizar commit y push; no se afirma una ejecución remota de estos cambios.
