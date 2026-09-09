# ADR 0002 — Identidad, tokens y permisos

Estado: implementado y aprobado como parte de la Fase 2. Esta entrega no inicia la Fase 3.

## Contexto

El monolito necesita registro público seguro, sesiones revocables y permisos efectivos en el servidor. React y la API comparten origen mediante Nginx o el proxy de Vite. No hay proveedores externos de identidad.

## Credenciales e identidad

- Un usuario tiene un único rol: CLIENTE, EMPLEADO o ADMIN. Flyway inserta el catálogo de roles, sin cuentas ni contraseñas predeterminadas.
- El registro acepta exclusivamente nombre, email y contraseña; siempre asigna CLIENTE. Jackson rechaza propiedades desconocidas, incluidos role, active, id y securityVersion.
- Email normalizado y único en PostgreSQL; la restricción también resuelve registros simultáneos.
- BCrypt con coste 12; mínimo 12 caracteres y máximo 72 bytes UTF-8, sin truncamiento silencioso. Login devuelve el mismo error para usuario inexistente, inactivo o contraseña incorrecta.
- Cambiar contraseña exige la actual, incrementa la versión de seguridad y revoca todas las sesiones en una transacción.
- La creación de personal y gestión de roles permanece en la Fase 8. Sus permisos se verifican ahora con usuarios de prueba en una base efímera; no se añade un mecanismo público de promoción ni un administrador automático.

## JWT y sesiones

JWT firmado HS256 con clave aleatoria de al menos 256 bits, suministrada por JWT_SECRET_BASE64 sin valor por defecto. Adecuado para el único backend emisor/verificador del monolito. La rotación operativa de claves queda para despliegue: reemplazar la clave invalida los accesos existentes, aunque una sesión refresh aún válida puede emitir uno nuevo.

El acceso dura como máximo 15 minutos y nunca más que su sesión. Incluye sub, sid, ver, role, jti, iss, aud, iat y exp. Spring valida algoritmo, firma, emisor, audiencia y tiempo con reloj inyectable. En cada petición se comprueban usuario activo, rol actual, versión de seguridad, dueño de la sesión, vencimiento y revocación en PostgreSQL. Se acepta deliberadamente esa consulta adicional para invalidar permisos y sesiones inmediatamente.

Refresh opaco de 32 bytes aleatorios; solo se guarda SHA-256 en PostgreSQL. La cookie RMS_REFRESH es HttpOnly, SameSite=Strict, Path=/api/v1/auth y Secure por defecto. Su familia tiene vencimiento absoluto a 7 días: rotar no prolonga indefinidamente la sesión.

refresh_sessions representa la familia, propietario y versión de seguridad al iniciarla. refresh_tokens conserva por separado cada hash y su consumo, para detectar reuso. Reutilizar un token consumido revoca toda la familia, incluidos sus JWT todavía vigentes. Esa revocación se confirma aunque la respuesta sea 401.

Logout revoca la sesión del bearer válido si existe y la identificada por la cookie; después borra la cookie. Sin credenciales es idempotente. Si el bearer está vencido, el frontend repite logout usando la cookie. El cambio de contraseña elimina también la cookie y obliga a iniciar sesión otra vez.

## Concurrencia

Login, refresh, logout, revocación y cambio de contraseña usan transacciones y bloqueos de PostgreSQL. Las modificaciones del propietario se serializan bloqueando usuario antes de sesión. Las consultas iniciales obtienen identificadores escalares; las entidades mutables se cargan después del bloqueo para evitar estado obsoleto en la caché de JPA.

Dos peticiones con el mismo refresh producen una rotación y un rechazo por reuso; al finalizar ambas, toda esa familia queda revocada. Es una política estricta: una pérdida de respuesta seguida de reintento puede exigir login. El cliente no reintenta automáticamente un refresh fallido por red.

React comparte una promesa de renovación entre solicitudes y usa Web Locks para serializar entre pestañas que comparten la cookie. En navegadores sin Web Locks se mantiene la protección del servidor; una carrera entre pestañas puede cerrar la sesión. Un contador local impide restaurar el acceso en memoria con una respuesta tardía después de salir.

## CSRF, origen y almacenamiento

CSRF protege operaciones basadas en cookies, incluido login y registro sin Bearer. Aclaración verificada durante Fase 3: Resource Server exceptúa las peticiones Bearer explícitas; no existe una desactivación global. GET /auth/csrf devuelve el nombre de cabecera y el token enmascarado de Spring; la cookie CSRF también es HttpOnly. La interfaz envía ambos de forma automática en todas sus mutaciones y renueva el token CSRF una vez ante CSRF_INVALID.

Cada mutación exige Origin en una lista explícita. No hay CORS abierto; incluso un cliente CLI debe enviar Origin autorizado y CSRF cuando corresponde al flujo con cookies. La configuración local usa localhost:3000, localhost:5173 y localhost:8080 (los puertos Compose configurables se reflejan en sus orígenes).

El acceso y los datos de sesión viven en memoria; no se guardan tokens en localStorage ni sessionStorage. Al recargar se recupera la sesión por refresh. Logout se comunica a otras pestañas mediante BroadcastChannel y se limpia la caché de TanStack Query.

Compose usa HTTP en loopback y configura AUTH_COOKIE_SECURE=false exclusivamente para ese entorno. Un despliegue HTTPS debe conservar Secure=true y ajustar la lista de orígenes.

## Permisos y auditoría

GET /users/me usa el sujeto autenticado. GET /users/{id} exige ADMIN. Las sesiones solo se enumeran y revocan por su propietario; un identificador ajeno responde 404. Estos recursos permiten demostrar autorización por rol y propiedad sin anticipar habitaciones ni reservas.

La auditoría registra registro, login, rotación, reuso, logout, revocación y cambio de contraseña, con actor cuando procede, recurso, fecha y requestId. Las operaciones exitosas comparten transacción con la auditoría. Los fallos de login se registran en transacción independiente. No se guardan cuerpos, contraseñas ni tokens. Los detalles JSONB y consulta administrativa se añadirán cuando existan operaciones que los necesiten.

Los límites de intentos son atómicos y compartidos en PostgreSQL: registro 20/IP/hora; login 100/IP/15 minutos y 10/email/15 minutos. Cuentan intentos válidos de entrada, tanto exitosos como fallidos, y sobreviven al rollback del login. La clave de contador se almacena como hash.

## Límites operativos

- Nginx agrupa las peticiones bajo su dirección de proxy para el límite por IP. No se confía ciegamente en X-Forwarded-For. Antes de un despliegue público se debe definir una cadena de proxies confiables y recalibrar límites; el límite por email ya es independiente.
- No se ha añadido una tarea de purga de familias vencidas, hashes históricos ni contadores inactivos. Se conserva evidencia de reuso; hace falta definir retención y limpieza antes de una operación prolongada.
- No hay recuperación de contraseña, verificación de email ni gestión administrativa de usuarios en esta fase, conforme al alcance aprobado.
- Una petición ya iniciada antes de una revocación podría completar una lectura; las mutaciones sensibles vuelven a validar bajo bloqueo. Las peticiones posteriores se rechazan.

## Evidencia

AuthenticationIT usa HTTP real y PostgreSQL Testcontainers: expiraciones con reloj controlado, permisos, CSRF, reuso, revocación, auditoría y concurrencia. Las pruebas frontend cubren formularios, validaciones y coordinación de tokens.

Referencias: [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html).
