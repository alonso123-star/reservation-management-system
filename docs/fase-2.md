# Fase 2 — Identidad y autenticación

Estado: implementada, verificada localmente y aprobada por el usuario; autorizado preparar staging.
Cierre de verificación: 9 de septiembre de 2026 (America/Lima).
No se ha iniciado la Fase 3. El commit y push de esta entrega requieren autorización posterior.

## Alcance entregado

- Registro público de clientes, con email único normalizado y rol CLIENTE obligatorio.
- Login con validación de credenciales y BCrypt.
- Spring Security con JWT de acceso, CSRF, validación de Origin y respuestas Problem Details.
- Refresh aleatorio en cookie HttpOnly, con hash en PostgreSQL, rotación, vencimiento absoluto, revocación y detección de reutilización.
- Logout, consulta y revocación de sesiones propias.
- Roles CLIENTE, EMPLEADO y ADMIN; permisos comprobados en el backend.
- GET /api/v1/users/me y consulta por ID reservada a ADMIN.
- Cambio de contraseña con verificación de la actual e invalidación de todas las sesiones.
- Rechazo de campos desconocidos para impedir asignación masiva de privilegios.
- Auditoría de operaciones de identidad y límites de intentos compartidos en PostgreSQL.
- Interfaz de registro, login, perfil, contraseña y sesiones, con validación y rutas protegidas.

Los endpoints de edición de perfil y administración de usuarios siguen pendientes de su alcance posterior. No se han creado habitaciones, tipos, reservas ni pagos. El detalle del contrato está en [auth-api.md](auth-api.md).

## Componentes y archivos importantes

| Archivo o módulo | Responsabilidad |
|---|---|
| backend/.../identity/application/AuthService.java | Transacciones de registro, login, rotación, revocación y cambio de contraseña |
| backend/.../identity/application/TokenService.java | Emisión de JWT y generación/hash de refresh |
| backend/.../identity/application/AuthRateLimiter.java | Contadores atómicos persistentes de intentos |
| backend/.../identity/infrastructure/SecurityConfiguration.java | Cadena de filtros, JWT, CSRF, orígenes y PasswordEncoder |
| backend/.../identity/infrastructure/SessionAuthenticationConverter.java | Validación de usuario, rol, versión y sesión en cada petición |
| backend/.../identity/domain y infrastructure | Familias de sesión, hashes consumidos y repositorios con bloqueos |
| backend/.../identity/api | Contratos de entrada/salida y cookies |
| backend/.../users | Usuario, rol, repositorios, perfil y permisos |
| backend/.../audit/AuditService.java | Auditoría transaccional y fallos de login |
| backend/.../shared/api/ApiErrors.java | Errores sin filtración de detalles internos y requestId |
| backend/src/main/resources/db/migration/V2__identity_and_sessions.sql | Tablas, índices, restricciones y catálogo de roles |
| backend/src/test/java/com/portfolio/reservation/AuthenticationIT.java | Pruebas HTTP, PostgreSQL, seguridad y concurrencia |
| frontend/src/features/auth/api/auth.ts | Acceso solo en memoria, CSRF, renovación y coordinación de peticiones/pestañas |
| frontend/src/features/auth/AuthProvider.tsx y useAuth.ts | Estado de sesión y protección de rutas |
| frontend/src/features/auth/AuthPages.tsx | Registro y login |
| frontend/src/features/auth/AccountPage.tsx | Perfil, contraseña y sesiones propias |
| frontend/src/features/auth/schemas.ts | Validación con Zod y límites UTF-8 de contraseña |
| frontend/src/app/App.tsx y Home.tsx | Rutas y navegación, conservando el estado técnico del entorno |
| scripts/setup-env.mjs | Generación local de secretos y conservación de valores ya configurados |
| compose.yaml y .github/workflows/ci.yml | Variables de seguridad y configuración efímera para CI |

En la tabla, backend/... equivale a backend/src/main/java/com/portfolio/reservation.

Se actualizaron README, plan inicial y documentación OpenAPI. Las decisiones están en [ADR 0002](adr/0002-identidad-autenticacion.md).

## Seguridad aplicada

JWT de hasta 15 minutos, validando HS256, firma, emisor, audiencia y vencimiento. La clave se proporciona por entorno y debe tener al menos 256 bits. El token de acceso no se persiste en almacenamiento web.

Refresh con 7 días de duración absoluta. Cada renovación consume su token y crea otro; el reuso revoca la familia completa, incluidos los JWT todavía vigentes. React serializa renovaciones de una pestaña y usa Web Locks entre pestañas cuando está disponible.

Las operaciones sensibles bloquean usuario antes de sesión. La revocación por reuso se confirma aun cuando la respuesta sea 401; el cambio de contraseña invalida sesiones dentro de la misma transacción. Las pruebas concurrentes verifican estos resultados sobre PostgreSQL real.

CSRF permanece activo en todas las mutaciones y se exige un Origin autorizado. Las cookies usan HttpOnly y SameSite Strict; Secure está activo por defecto y se desactiva únicamente en el Compose HTTP local.

BCrypt usa coste 12; la contraseña requiere al menos 12 caracteres y no puede superar 72 bytes UTF-8. Registro siempre asigna CLIENTE y rechaza role, active y otros campos no permitidos.

## Verificaciones realizadas

Los resultados siguientes corresponden a la implementación final. Al retomar la tarea se revisaron las evidencias guardadas y se completó la redirección de la ruta protegida y la revisión de Git; no se repitieron compilaciones ni suites ya aprobadas.

| Comprobación | Resultado |
|---|---|
| Docker: backend-tests / Maven verify | 21 pruebas, 0 fallos, 0 errores, 0 omitidas |
| AuthenticationIT | 17 pruebas de identidad con servidor HTTP y PostgreSQL Testcontainers |
| FoundationIT | 4 pruebas de migraciones, entorno, API y seguridad básica |
| Frontend: npm run lint | Correcto, sin advertencias finales |
| Frontend: npm test | 15 pruebas en 4 archivos, todas aprobadas |
| Frontend: npm run build | TypeScript y Vite correctos |
| Docker Compose up --build --wait | Imágenes construidas y tres servicios saludables |
| Migración sobre la base local existente | V1 validada y V2 aplicada correctamente; volumen conservado |
| scripts/smoke.mjs | Frontend, assets, proxy, backend, readiness con PostgreSQL, OpenAPI y Swagger correctos |
| Prueba HTTP de identidad mediante Nginx | Registro, rechazo de privilegios, login, perfil, permiso ADMIN, refresh, sesiones, logout y acceso revocado correctos |
| Navegador | Registro con validación de campos; login, perfil/sesiones, recarga con recuperación por refresh y logout correctos |
| Navegador al retomar | /account sin sesión redirige a /login |
| Git y secretos | Índice vacío, diff sin errores de espacios, archivos locales/generados ignorados; secretos PostgreSQL/JWT ausentes de candidatos a Git |

Comandos principales usados desde la raíz:

~~~powershell
docker compose --profile test run --rm backend-tests
npm.cmd --prefix frontend run lint
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
docker compose --progress plain up --build --wait --wait-timeout 240
docker compose ps
node scripts/smoke.mjs
git diff --check
~~~

Los informes JUnit permanecen en backend/target/failsafe-reports, excluidos de Git. El test HTTP local adicional se ejecutó mediante un auxiliar en .tools, también excluido. Ese auxiliar es evidencia local, no una suite incorporada a CI.

Cobertura de seguridad: credenciales incorrectas, usuario desactivado, campos de privilegios, roles y propiedad, firma/issuer/audience inválidos, token vencido, refresh vencido, sesiones revocadas, reuso de refresh, concurrencia de refresh, cambio de contraseña concurrente, CSRF/origen, persistencia de auditoría y límites de intentos.

La validación frontend comprueba formularios y política de contraseña, coordinación de refresh, reintento tras 401, rechazo de sesiones revocadas, actualización de CSRF y respuestas tardías después de logout. El cambio de contraseña se comprobó con las pruebas HTTP y la validación del formulario; no se realizó un cambio manual desde el navegador.

La prueba local creó un cliente ficticio bajo example.test para verificar la interfaz; sus sesiones se cerraron. No se crearon cuentas ADMIN/EMPLEADO en la base local ni se incorporaron datos personales reales. Los roles privilegiados se ejercitaron con fixtures en PostgreSQL efímero.

Durante la implementación se corrigieron un error de tipos de Web Locks y una advertencia de exportaciones Fast Refresh. Las últimas comprobaciones de lint, pruebas y compilación pasaron. Maven informa advertencias no bloqueantes por el agente dinámico de Mockito y una API obsoleta utilizada en pruebas.

## Estado de Git

La rama sigue siendo main y conserva origin/main como upstream. HEAD sigue en el commit de Fase 1:

23989258aacd99903cdcdcad5505d78cec918c3a — chore: bootstrap phase 1 executable foundation

Al cerrar las verificaciones, los cambios de Fase 2 estaban en el árbol de trabajo sin preparar en el índice. Después, el usuario aprobó la fase y autorizó revisar y preparar los archivos correctos en staging. El árbol no estará limpio hasta confirmar los cambios: no se ha autorizado el commit. No hubo operaciones de escritura contra GitHub.

.env, node_modules, dist, target y .tools continúan ignorados. Se revisaron los archivos candidatos buscando los valores reales de las claves PostgreSQL/JWT y patrones de claves privadas/tokens de acceso; no se encontraron coincidencias. Esta revisión no equivale a una auditoría de seguridad exhaustiva.

## Problemas y decisiones pendientes

No se detectaron bloqueos funcionales en el alcance local verificado de la Fase 2.

Antes de un despliegue público deben resolverse los siguientes aspectos, documentados en el ADR:

1. Configurar HTTPS, cookies Secure y orígenes de producción.
2. Definir proxies confiables y ajustar el límite por IP: en Compose Nginx agrupa usuarios bajo la IP del proxy; el límite por email ya es independiente.
3. Definir retención y purga de sesiones vencidas, hashes consumidos, contadores y auditoría.
4. Definir el aprovisionamiento del primer administrador cuando se aborde administración. No existe una cuenta privilegiada con contraseña predeterminada.

La política estricta de reuso puede cerrar una sesión si se pierde la respuesta de refresh y se repite el mismo token. Se conserva esa garantía y se evita el reintento automático de red; en navegadores sin Web Locks una carrera entre pestañas también puede exigir login.

La CI de estos cambios no se ha ejecutado en GitHub, porque no se ha hecho push. La suite E2E completa prevista para las fases posteriores todavía no existe.

La Fase 2 está aprobada y su preparación en staging autorizada. El siguiente paso es aprobar el mensaje y la creación del commit local. Iniciar la Fase 3 y realizar push necesitan autorizaciones explícitas posteriores.
