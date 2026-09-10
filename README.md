# Reservation Management System

Sistema Full Stack de reservas de hotel, construido como un monolito modular.

**Estado:** Fases 0–3 aprobadas y sincronizadas con GitHub en `2800709`. Fase 4 — Disponibilidad implementada localmente para revisión; consulta pública por fechas, huéspedes, tipo y precio con estimación de estancia. Véase [docs/fase-4.md](docs/fase-4.md). Sin staging, commit ni push de esta entrega. Fase 5 no iniciada.

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
- Búsqueda pública por estancia y huéspedes, tipo, precio y paginación, con noches y total estimado calculados por el backend. La consulta es orientativa: todavía no existe persistencia de reservas y no bloquea habitaciones.

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

`verify` ejecuta las pruebas de integración con Maven Failsafe. `test` por sí solo no ejecuta `FoundationIT`, `AuthenticationIT` ni `CatalogIT`. El conjunto incluye 39 pruebas backend con PostgreSQL real y 35 pruebas frontend. En Windows, si los procesos de Vitest agotan el tiempo de inicio, utiliza `npm.cmd test -- --pool=threads --maxWorkers=1`; ejecuta la misma suite completa con un worker.

Prueba del conjunto ya arrancado, desde la raíz y con Node.js:

```powershell
node scripts/smoke.mjs
```

Si cambias `FRONTEND_PORT`, indica la URL correspondiente en `SMOKE_BASE_URL`.

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

- `backend/`: módulos identity, users, rooms, audit y shared, migraciones y pruebas de integración.
- `frontend/`: aplicación React, catálogo público, inventario del personal, identidad, cliente HTTP y pruebas.
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

Los módulos de negocio se crearán cuando comience su fase, evitando carpetas vacías y código anticipado.

## Configuración y límites

Flyway administra el esquema y Hibernate utiliza `ddl-auto=validate`. V1 instala `btree_gist`; V2 añade usuarios, roles, familias de sesión, hashes de refresh, auditoría y contadores de intentos. V3 añade exclusivamente `room_types` y `rooms`, con restricciones e índices de catálogo. No hay tablas de reservas ni pagos. Nunca se usa `ddl-auto=update` para modificar el esquema ni se reescriben migraciones históricas.

La comprobación de readiness incluye PostgreSQL. Un backend vivo sin acceso a su base no se considera listo. El endpoint no devuelve detalles internos.

Swagger está habilitado para desarrollo. `API_DOCS_ENABLED=false` permite desactivarlo al configurar el backend. Solo se expone el endpoint Actuator de salud.

Para probar la aplicación, crea una cuenta desde **Crear cuenta** y después inicia sesión. No hay usuarios privilegiados predeterminados. El registro siempre asigna CLIENTE. Crear personal y administrar roles corresponde a la Fase 8; las pruebas usan fixtures aislados para comprobar EMPLEADO y ADMIN.

El JWT de acceso dura 15 minutos y se conserva solo en memoria. El refresh dura 7 días absolutos, rota y viaja en cookie HttpOnly/SameSite Strict. Reutilizar un refresh consumido revoca su familia. Logout invalida la sesión y cambiar contraseña invalida todas. La API comprueba usuario, rol, versión y sesión en PostgreSQL en cada petición autenticada.

Origin es obligatorio para todas las mutaciones, incluso desde herramientas HTTP. CSRF protege operaciones con cookies; Spring Resource Server exceptúa peticiones Bearer explícitas. React también envía CSRF con esas peticiones. Consulta los contratos de identidad y catálogo para la secuencia. Compose utiliza cookies sin Secure exclusivamente por su HTTP local; para HTTPS se debe usar Secure=true y configurar los orígenes autorizados. El backend por defecto exige Secure.

Las credenciales de PostgreSQL son locales; su usuario administra la base para permitir las migraciones. Para despliegue quedan por definir permisos separados de migración, proxies confiables para límites por IP y retención/purga de sesiones y auditoría. El ADR detalla estos límites.

Catálogo público: `/catalog/types` y `/catalog/rooms`. Inventario del personal: `/staff/catalog/types` y `/staff/catalog/rooms`. Los tipos inactivos y las habitaciones no visibles quedan fuera del catálogo público. `ACTIVE` es estado operativo; no indica disponibilidad para fechas. La tarifa base usa PEN por defecto. No se implementan reservas ni disponibilidad en esta entrega.

La CI está configurada en GitHub. Las comprobaciones de Fase 3 son locales hasta autorizar su commit y push; no se afirma una ejecución remota de estos cambios.
