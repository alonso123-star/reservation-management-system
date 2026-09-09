# Contrato de identidad — Fase 2

Prefijo: /api/v1. OpenAPI ejecutable: http://localhost:3000/v3/api-docs. Swagger: http://localhost:3000/swagger-ui/index.html.

## Secuencia de autenticación

1. Obtener GET /auth/csrf y conservar las cookies. Su respuesta contiene headerName y token; enviar ese token en la cabecera indicada.
2. En toda mutación enviar también Origin con el origen autorizado de la aplicación, Content-Type: application/json cuando haya cuerpo, y las cookies.
3. Registrar un cliente y después iniciar sesión. El registro no inicia sesión automáticamente.
4. Conservar accessToken solo en memoria y enviarlo como Authorization: Bearer en recursos protegidos. El refresh solo llega por cookie HttpOnly.
5. Renovar con POST /auth/refresh. Sustituir el acceso en memoria; el navegador actualiza la cookie automáticamente.
6. Logout o cambio de contraseña eliminan el acceso en memoria y requieren login.

En Swagger, Authorize configura el bearer. Origin sigue siendo obligatorio en toda mutación. Aclaración verificada en Fase 3: Spring Resource Server exceptúa peticiones Bearer explícitas del filtro CSRF; las operaciones basadas en cookies requieren CSRF. La interfaz React envía la protección también con Bearer y mantiene el flujo completo descrito aquí. Las menciones CSRF de la tabla indican ese flujo recomendado del cliente.

## Endpoints implementados

| Método y ruta | Cuerpo / respuesta | Permiso y resultado |
|---|---|---|
| GET /auth/csrf | Respuesta: headerName, token | Público; 200 |
| POST /auth/register | Entrada: name, email, password. Respuesta: UserView | Público con CSRF/origen; 201, CLIENTE forzado |
| POST /auth/login | Entrada: email, password. Respuesta: AccessView y cookie | Público con CSRF/origen; 200 |
| POST /auth/refresh | Sin cuerpo; cookie refresh. Respuesta: AccessView y nueva cookie | CSRF/origen y sesión válida; 200 |
| POST /auth/logout | Sin cuerpo; cookie y bearer opcional | CSRF/origen; 204 idempotente sin credenciales |
| GET /users/me | Respuesta: UserView | Autenticado; 200 |
| GET /users/{id} | Respuesta: UserView | ADMIN; 200 |
| PUT /users/me/password | Entrada: currentPassword, newPassword | Autenticado, CSRF/origen; 204, revoca todas las sesiones |
| GET /users/me/sessions | Lista de SessionView activas | Propietario autenticado; 200 |
| DELETE /users/me/sessions/{id} | Sin cuerpo | Propietario, CSRF/origen; 204; ajena/inexistente 404 |

UserView: id UUID, name, email, role (CLIENTE/EMPLEADO/ADMIN), createdAt (ISO-8601).
AccessView: accessToken, tokenType Bearer, expiresIn (segundos), user (UserView).
SessionView: id UUID, createdAt, expiresAt, current.

No se devuelven hashes, refresh en JSON, estado interno ni versión de seguridad. El formulario de repetición de contraseña se valida en React y no se envía a la API. Los cuerpos con campos desconocidos se rechazan.

## Errores

Formato application/problem+json con type, title, status, detail, code y requestId. Las validaciones incluyen errors por campo. X-Request-Id permite correlacionar respuesta y auditoría.

| Estado | Casos |
|---|---|
| 400 | VALIDATION_ERROR, INVALID_REQUEST, PASSWORD_POLICY, CURRENT_PASSWORD_INVALID, PASSWORD_UNCHANGED |
| 401 | UNAUTHENTICATED, INVALID_CREDENTIALS, INVALID_SESSION, SESSION_REVOKED |
| 403 | ACCESS_DENIED, CSRF_INVALID, ORIGIN_NOT_ALLOWED |
| 404 | USER_NOT_FOUND, SESSION_NOT_FOUND |
| 409 | EMAIL_UNAVAILABLE; DATA_CONFLICT si una restricción gana una carrera |
| 429 | RATE_LIMITED |
| 500 | INTERNAL_ERROR sin detalles internos |

Un bearer vencido o revocado enviado incluso a un endpoint público puede producir 401. Logout permite repetir la petición sin ese bearer, conservando cookie y CSRF.

No están implementadas las rutas de edición de perfil, listado o administración de usuarios, ni las rutas de catálogo, reservas y pagos del plan general.
