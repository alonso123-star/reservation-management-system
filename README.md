# Reservation Management System

Sistema Full Stack de reservas de hotel, construido como un monolito modular.

**Estado:** Fase 0 aprobada. Fase 1 completada y verificada localmente. La autenticación, los roles y las funciones de negocio se implementarán a partir de las siguientes fases, previa autorización.

## Incluido en esta base

- React + TypeScript con Vite y una pantalla que consulta la disponibilidad real del entorno.
- Spring Boot, Java 21, Spring Data JPA y Actuator.
- PostgreSQL 18 y una migración Flyway que prepara la extensión `btree_gist`.
- OpenAPI/Swagger para el endpoint técnico de información.
- Docker Compose con comprobaciones de salud, volumen persistente y proxy Nginx.
- Pruebas JUnit con PostgreSQL mediante Testcontainers y pruebas de interfaz con Vitest.
- CI de frontend, backend y arranque completo con Compose.
- Git, Maven Wrapper y lockfile de npm.

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

Requisitos: Docker Desktop con motor Linux activo y Docker Compose. Esta ruta **no requiere Java ni Maven instalados en el equipo**.

Desde la raíz del proyecto, en PowerShell:

```powershell
Copy-Item .env.example .env
```

Hazlo solo si todavía no existe `.env`. El archivo local puede haber sido creado durante la configuración inicial. Elige una contraseña local en `POSTGRES_PASSWORD`; no publiques ese archivo.

```powershell
docker compose config --quiet
docker compose up --build --wait --wait-timeout 240
```

La primera construcción descarga imágenes y dependencias; puede tardar varios minutos.

| Servicio | Dirección predeterminada |
|---|---|
| Aplicación | http://localhost:3000 |
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

`verify` ejecuta las pruebas de integración con Maven Failsafe. `test` por sí solo no ejecuta `FoundationIT`.

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
cd backend
.\mvnw.cmd spring-boot:run
```

Este ejemplo requiere que `.env` mantenga el formato simple `CLAVE=valor` de la plantilla.

## Estructura actual

- `backend/`: aplicación, configuración, endpoint técnico, migración y pruebas de integración.
- `frontend/`: aplicación React, cliente HTTP de salud y pruebas de conectividad.
- `infrastructure/nginx/`: archivos estáticos y proxy al backend.
- `scripts/smoke.mjs`: comprobación HTTP del conjunto.
- `.github/workflows/ci.yml`: verificación automática al hacer push o abrir un pull request.
- `docs/plan-inicial.md`: planificación aprobada.
- `docs/adr/0001-base-ejecutable.md`: decisiones de la Fase 1.
- `docs/fase-1.md`: alcance y registro de verificación.

Los módulos de negocio se crearán cuando comience su fase, evitando carpetas vacías y código anticipado.

## Configuración y límites

Flyway administra el esquema y Hibernate utiliza `ddl-auto=validate`. La primera migración instala únicamente `btree_gist`; no hay tablas de usuarios, reservas ni pagos. Nunca se usa `ddl-auto=update` para modificar el esquema.

La comprobación de readiness incluye PostgreSQL. Un backend vivo sin acceso a su base no se considera listo. El endpoint no devuelve detalles internos.

Swagger está habilitado para desarrollo. `API_DOCS_ENABLED=false` permite desactivarlo al configurar el backend. Solo se expone el endpoint Actuator de salud.

Spring Security, JWT, registro, login, roles y sesiones están pendientes de Fase 2. No hay credenciales de acceso a la aplicación ni datos reales. Las credenciales de PostgreSQL del Compose son exclusivamente locales; su usuario administra la base para permitir las migraciones. La separación de permisos de migración y ejecución se evaluará para despliegue.

La CI está preparada para GitHub, pero su ejecución remota requiere subir el repositorio a un remoto autorizado. Inicializar Git no crea un repositorio remoto ni publica archivos.
