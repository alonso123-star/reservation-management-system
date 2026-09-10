# API de catálogo — Fase 3

Prefijo `/api/v1`. Catálogo de características, tarifas base e inventario operativo. No calcula disponibilidad por fechas. Swagger UI: `/swagger-ui/index.html`; contrato generado: `/v3/api-docs`.

## Rutas y permisos

| Método y ruta | Permiso | Respuesta |
|---|---|---|
| GET `/room-types` | Público | Página de tipos activos |
| GET `/room-types/{id}` | Público | Tipo activo o 404 |
| GET `/rooms` | Público | Página de habitaciones visibles |
| GET `/rooms/{id}` | Público | Habitación visible o 404 |
| GET `/staff/room-types` | ADMIN, EMPLEADO | Página completa con actividad y versión |
| GET `/staff/room-types/{id}` | ADMIN, EMPLEADO | Tipo administrativo |
| GET `/staff/rooms` | ADMIN, EMPLEADO | Página completa con estado, actividad y versión |
| GET `/staff/rooms/{id}` | ADMIN, EMPLEADO | Habitación administrativa |
| POST `/room-types` | ADMIN | 201, tipo creado |
| PATCH `/room-types/{id}` | ADMIN | 200, tipo actualizado |
| POST `/rooms` | ADMIN | 201, habitación creada |
| PATCH `/rooms/{id}` | ADMIN | 200, habitación actualizada |
| PATCH `/rooms/{id}/status` | ADMIN, EMPLEADO | 200, habitación actualizada |

No hay DELETE: desactivar preserva los registros. CLIENTE tiene los mismos permisos de lectura pública que un visitante anónimo; no puede consultar inventario ni escribir. Las anotaciones de autorización en controlador y servicio verifican los roles efectivos obtenidos por la infraestructura de identidad existente.

## Representaciones

RoomType público: `id`, `name`, `description`, `capacity`, `basePrice`, `currency`. Room público: `id`, `code`, `floor`, `roomType` con la representación pública anterior. No se serializan entidades JPA ni versiones, actividad o timestamps internos en estas rutas.

El inventario añade `active`, `version`, `createdAt`, `updatedAt`. Room añade también `operationalStatus`; su tipo anidado contiene los campos administrativos del tipo.

Un tipo es público cuando está activo. Una habitación es visible cuando está activa, su tipo está activo y su estado es `ACTIVE`. Consultar directamente un recurso oculto responde 404. Los filtros públicos nunca anulan estas condiciones.

## Creación y edición

Crear tipo:

```json
{
  "name": "Doble",
  "description": "Dos camas individuales",
  "capacity": 2,
  "basePrice": "125.50",
  "active": true
}
```

Todos los campos de creación son obligatorios; descripción puede ser vacía. Nombre: 1–100 caracteres tras recortar extremos; unicidad sin distinguir mayúsculas. Descripción: hasta 2000 caracteres. Capacidad: entero 1–1000. Precio: positivo, hasta 10 enteros y 2 decimales. Java usa BigDecimal y PostgreSQL NUMERIC(12,2); se acepta número JSON o texto decimal, y el formulario envía texto decimal para conservar la entrada. La respuesta es un número JSON; React solo lo formatea, sin cálculos monetarios. Moneda única por hotel, PEN por defecto mediante `hotel.currency` (variable Spring `HOTEL_CURRENCY` si se configura en el entorno del backend).

Crear habitación:

```json
{
  "code": "A-101",
  "roomTypeId": "UUID de un tipo existente",
  "floor": 1,
  "operationalStatus": "ACTIVE",
  "active": true
}
```

Código obligatorio, hasta 30 caracteres; se recorta y convierte a mayúsculas antes de comprobar unicidad. Piso entero -5–200. Estados: `ACTIVE`, `MAINTENANCE`, `OUT_OF_SERVICE`. Actividad administrativa y estado operativo son independientes. Se permite asociar un tipo inactivo al inventario; la habitación permanece oculta al público.

En PATCH se exige `version` obtenida del inventario. Los campos omitidos o null conservan su valor; null no borra campos. `description: ""` vacía la descripción. Los campos desconocidos se rechazan con 400, incluidos identificadores, roles o campos administrativos en el endpoint de estado.

```json
{ "version": 3, "capacity": 3, "basePrice": "150.00" }
```

Activar/desactivar usa `{ "version": 3, "active": false }`. Cambiar solo estado admite exclusivamente:

```json
{ "version": 5, "operationalStatus": "MAINTENANCE" }
```

Una versión desactualizada produce 409. La comprobación de la versión recibida y `@Version` en la actualización SQL cubren tanto formularios antiguos como transacciones simultáneas. Recargar el registro y revisar los cambios antes de volver a guardar; no se reintenta la escritura automáticamente.

## Filtros y paginación

| Recurso | Filtros |
|---|---|
| Tipos | `q` (nombre, contiene, sin distinguir mayúsculas), `active`, `minCapacity`, `maxCapacity` |
| Habitaciones | `q` (código), `roomTypeId` (UUID), `floor`, `operationalStatus`, `active` |

`page` comienza en 0 (máximo 100000); `size` es 1–100, por defecto 20. Se admite un orden `sort=campo,asc` o `sort=campo,desc`. Tipos: `name`, `capacity`, `basePrice`, `id`; habitaciones: `code`, `floor`, `operationalStatus`, `id`. Valores predeterminados: `name,asc` y `code,asc`. Se añade `id` como desempate estable. Se rechazan campos/direcciones de orden no permitidos, límites inválidos y capacidad mínima superior a la máxima.

Ejemplo: `/api/v1/room-types?q=doble&minCapacity=2&page=0&size=12&sort=basePrice,asc`.

```json
{ "items": [], "page": 0, "size": 12, "totalElements": 0, "totalPages": 0 }
```

La selección, ordenación, conteo y paginación ocurren en PostgreSQL mediante Specifications y Pageable. El selector de tipos también consulta páginas del servidor. No hay filtros por fecha, check-in o check-out.

## Autenticación, errores y auditoría

Las rutas protegidas requieren `Authorization: Bearer ...`; una cookie de refresh por sí sola no autoriza catálogo. Toda escritura requiere un Origin autorizado. Se conserva la política de Fase 2: CSRF protege operaciones con cookies; Spring Resource Server exceptúa las peticiones Bearer explícitas. El cliente React envía además CSRF en todas las escrituras y reutiliza el flujo existente de refresh. No se desactiva CSRF globalmente ni se almacenan tokens en localStorage.

Los errores usan el mismo Problem Details (`application/problem+json`): `type`, `title`, `status`, `detail`, `instance`, `code`, `requestId` y, cuando aplica, `errors` por campo. 400: validación/JSON/filtros; 401: sesión ausente o inválida; 403: rol, CSRF u Origin; 404: recurso inexistente/oculto; 409: `TYPE_NAME_TAKEN`, `ROOM_CODE_TAKEN`, `STALE_VERSION`. El frontend conserva los datos del formulario ante errores y ofrece recargar en conflictos.

Las escrituras registran actor, acción, recurso, UUID y requestId usando AuditService, dentro de la misma transacción. Acciones: `ROOM_TYPE_CREATED`, `ROOM_TYPE_UPDATED`, `ROOM_TYPE_ACTIVATED`, `ROOM_TYPE_DEACTIVATED`, `ROOM_CREATED`, `ROOM_UPDATED`, `ROOM_ACTIVATED`, `ROOM_DEACTIVATED`, `ROOM_STATUS_CHANGED`. Si cambia actividad junto con otros campos se registra la acción de activación/desactivación; el PATCH general de habitación registra `ROOM_UPDATED` aunque también cambie estado. Los intentos que revierten por restricciones o concurrencia no generan auditoría de éxito. No se registran cuerpos de peticiones, contraseñas ni tokens. La consulta visual de auditoría queda para su fase futura.

No hay cuentas privilegiadas predeterminadas ni nuevas rutas para asignar roles. El uso de ADMIN/EMPLEADO requiere usuarios previamente provisionados de forma controlada. Las pruebas de catálogo crean esos roles en su PostgreSQL efímero; no modifican usuarios de desarrollo.

## Integración con reservas — Fase 5

Las ediciones de habitación bloquean su fila; las ediciones de tipo bloquean su tipo. Crear una reserva bloquea primero habitación y después tipo, y vuelve a comprobar elegibilidad y tarifa dentro de esa transacción. Se mantiene también `version` para detectar formularios antiguos.

Con reservas `CONFIRMED` o `CHECKED_IN` cuya salida sea posterior al día actual del hotel, se rechaza desactivar la habitación, pasarla a mantenimiento/fuera de servicio o cambiar su tipo. Desactivar un tipo o reducir su capacidad por debajo de los huéspedes de esas reservas también produce **409 `ROOM_HAS_RESERVATIONS`**. El guard comprueba las reservas después de obtener el bloqueo; la lectura no toma bloqueos adicionales de habitación al editar un tipo.

Cambiar la tarifa base está permitido y no altera el precio histórico de reservas existentes. No se crean bloqueos temporales de inventario ni operaciones de recepción. El contrato de reservas y las pruebas se describen en [fase-5.md](fase-5.md).
