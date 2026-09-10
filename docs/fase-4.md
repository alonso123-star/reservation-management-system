# Fase 4 — Disponibilidad

Entrega local para revisión, sobre `28007099953fd774a6dd50a5fb6c85c31dd14311` (Fase 3 aprobada y sincronizada). No autoriza ni inicia Fase 5. Los cambios se conservan sin staging, commit ni push.

## Alcance y límite actual

Se incorpora una búsqueda pública de habitaciones elegibles para una estancia: fechas, huéspedes, tipo, precio nocturno, orden y paginación. Solo devuelve habitaciones activas, operativas (`ACTIVE`), con tipo activo y capacidad suficiente. Calcula noches y total estimado en el backend.

**Fase 4 no crea reservas. Una búsqueda no bloquea habitaciones. Actualmente no existe persistencia de reservas.** Por ello las fechas determinan la duración y estimación, pero no excluyen ocupación. La interfaz y OpenAPI explican este límite; los resultados no confirman una habitación para esas fechas.

En Fase 5 la disponibilidad se ampliará para excluir reservas bloqueantes solapadas **sin cambiar el contrato público de búsqueda**. Esa exclusión se añadirá a la Specification, antes de paginar y contar, como predicado correlacionado que compruebe el solapamiento del intervalo. No se implementa todavía ninguna tabla, entidad, repositorio ni servicio de reservas, ni una consulta contra estructuras inexistentes. La garantía transaccional al crear una reserva pertenece a Fase 5; una búsqueda siempre será orientativa y podrá quedar desactualizada.

## Endpoint y contrato público

`GET /api/v1/rooms/availability`, accesible sin sesión ni JWT. No recibe cuerpo ni modifica datos. Los endpoints existentes de catálogo e inventario conservan su contrato.

| Parámetro | Obligatorio | Regla |
|---|---|---|
| `checkIn` | Sí | Fecha ISO `YYYY-MM-DD`, entrada incluida |
| `checkOut` | Sí | Fecha ISO `YYYY-MM-DD`, salida excluida, posterior a entrada |
| `guests` | Sí | Entero positivo, para una habitación |
| `roomTypeId` | No | UUID del tipo; mantiene la nomenclatura del catálogo |
| `minPrice` | No | Precio nocturno mínimo inclusivo, ≥ 0 |
| `maxPrice` | No | Precio nocturno máximo inclusivo, ≥ mínimo |
| `page` | No | Base cero, 0–100000, predeterminado 0 |
| `size` | No | 1–100, predeterminado 20 |
| `sort` | No | Un campo y dirección `campo,asc` o `campo,desc`; predeterminado `code,asc` |

Precios de filtro: hasta diez dígitos enteros y dos decimales, coherentes con el `NUMERIC(12,2)` del catálogo. Campos de orden permitidos: `code`, `floor`, `basePrice`, `capacity`, `id`. Se añade `id ASC` para desempatar, salvo cuando `id` es el orden solicitado. Los alias `basePrice` y `capacity` se traducen a atributos del tipo después de validar la lista permitida; nunca se acepta una ruta arbitraria de entidad.

Ejemplo: `/api/v1/rooms/availability?checkIn=2026-10-10&checkOut=2026-10-12&guests=2&minPrice=100.00&maxPrice=200.00&page=0&size=20&sort=basePrice,asc`.

Respuesta HTTP 200, ejemplo ilustrativo (no introduce datos en la base local):

```json
{
  "items": [{
    "id": "123e4567-e89b-12d3-a456-426614174001",
    "code": "101",
    "floor": 1,
    "roomType": {
      "id": "123e4567-e89b-12d3-a456-426614174000",
      "name": "Doble",
      "description": "Dos camas",
      "capacity": 2,
      "basePrice": 125.50,
      "currency": "PEN"
    },
    "nights": 2,
    "estimatedTotal": 251.00
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Se reutilizan `PageView` y `CatalogViews.PublicType`; `AvailabilityView` añade datos de estancia sin exponer versiones ni información administrativa. Sin coincidencias: HTTP 200, `items: []`, totales cero. Una página fuera de los resultados pero dentro del rango permitido también devuelve lista vacía, conservando los totales de la consulta.

Entradas inválidas devuelven HTTP 400 `application/problem+json` mediante `ApiErrors`: `type`, `title`, `status`, `detail`, `code`, `requestId` y, en validación de campos, `errors`. Se reutilizan los códigos de validación/conversión/paginación existentes; `INVALID_STAY` identifica salida igual o anterior a entrada y `INVALID_FILTER` el rango de precios invertido.

## Fechas, capacidad y precio

Se usan `LocalDate` y el intervalo hotelero `[checkIn, checkOut)`. Las noches se calculan con `ChronoUnit.DAYS.between`, sin horas, zona UTC ni aritmética de milisegundos. Del 10 al 12 son dos noches; entrada y salida iguales son inválidas. Se respetan fechas reales y años bisiestos. No se añaden restricciones sobre fechas pasadas, horizonte futuro o duración máxima, porque no están aprobadas en el plan.

La capacidad se filtra en PostgreSQL como `roomType.capacity >= guests`; capacidad exacta es válida. `MAINTENANCE`, `OUT_OF_SERVICE`, habitación inactiva o tipo inactivo siempre quedan excluidos. No se almacena un booleano de disponibilidad.

El total estimado es `basePrice × nights` mediante `BigDecimal`, conservando dos decimales del precio persistido. La moneda procede de la misma configuración `hotel.currency` del catálogo, con `PEN` predeterminado. Los filtros se aplican al precio **por noche**, no al total de estancia. La interfaz solo formatea el precio y el total recibido con `Intl.NumberFormat`; no calcula noches ni importes. No se incorporan impuestos, descuentos, pagos, cambios de tarifa ni confirmaciones de precio.

## Arquitectura y PostgreSQL

Se extiende el módulo `rooms`:

- `AvailabilityController`: contrato GET, validación y documentación OpenAPI.
- `AvailabilityQuery`: fechas tipadas, huéspedes y filtros validados.
- `AvailabilityService`: transacción de solo lectura, reglas cruzadas, orden permitido y estimación.
- `AvailabilitySpecifications`: predicados de elegibilidad y filtros componibles.
- `AvailabilityView`: DTO público con estancia estimada.

Se reutiliza `RoomRepository.findAll(Specification, Pageable)` con su `@EntityGraph(roomType)`, evitando cargar toda la colección y evitando consultas por habitación al serializar el tipo. El filtrado, orden, límite y conteo se realizan en PostgreSQL. Solo se transforma en DTO el contenido de la página obtenida; nunca se filtra una página en memoria.

Se inspeccionaron los siete índices existentes de las dos tablas: claves primarias, `uq_room_types_name`, `uq_rooms_code`, `idx_room_types_catalog(active, capacity, id)`, `idx_rooms_type(room_type_id)` e `idx_rooms_catalog(active, operational_status, floor, id)`. El EXPLAIN de la consulta de elegibilidad en la base local mostró uso de `idx_rooms_catalog` y `room_types_pkey`, con filtros del tipo y orden final. Esta base pequeña no demuestra rendimiento a escala; no justifica añadir índices especulativos de precio.

**No se necesita migración V4:** no cambió el esquema y los índices actuales cubren esta ampliación. V1, V2 y V3 permanecen intactas. La extensión `btree_gist` ya existía desde V1; esta fase no añade ni usa GiST, rangos PostgreSQL, exclusiones ni bloqueos de reservas.

## Seguridad y frontend

La única modificación de seguridad añade explícitamente el GET de disponibilidad a la lista pública. No se cambia `AuthService`, JWT, refresh, revocación, CSRF, roles, permisos de edición ni política de origen. El inventario sigue protegido para ADMIN/EMPLEADO y las mutaciones mantienen sus permisos.

La ruta React `/availability` se abre desde «Buscar estancia» en la navegación. Reutiliza el cliente HTTP público, TanStack Query, React Hook Form, Zod, `TypePicker`, estilos de catálogo y enlaces a detalles. Hay fechas obligatorias, huéspedes, filtro opcional de tipo, precios, orden y tamaños de página 6/12/24. La selección de tipo mantiene su búsqueda y paginación actuales.

La consulta se envía al pulsar buscar tras validar; nuevos filtros vuelven a página cero. Repetir la misma búsqueda vuelve a consultar el servidor. Se muestran estado inicial, carga, errores con reintento, lista vacía, resultados, precio nocturno, moneda, noches y total estimado. El resumen identifica los filtros de estancia aplicados. No existe botón funcional para reservar ni se introducen datos de catálogo en la base de desarrollo.

OpenAPI describe los nueve parámetros, obligatoriedad, ejemplos, reglas, orden, acceso anónimo, respuesta paginada 200 y ProblemDetail 400. La versión informativa de la aplicación pasa a `0.4.0`, fase 4; no se cambian dependencias.

## Verificación de esta entrega

Verificación local realizada el 9 de septiembre de 2026 (America/Lima; los contenedores registran UTC del día 10).

| Comprobación | Resultado |
|---|---|
| `docker compose --profile test run --rm backend-tests` | **BUILD SUCCESS**, 76 pruebas, 0 fallos, 0 errores, 0 omitidas; 9 min 3 s |
| `AuthenticationIT` | 17 aprobadas, regresión Fase 2 |
| `CatalogIT` | 18 aprobadas, regresión Fase 3 |
| `FoundationIT` | 4 aprobadas; identidad informativa actualizada a fase 4 |
| `AvailabilityIT` | 37 aprobadas, incluyendo los casos parametrizados de límites |
| `npm.cmd test -- --pool=threads --maxWorkers=1` | 6 archivos, **58 pruebas aprobadas**, sin errores; 53,95 s |
| Regresión frontend | Las 35 pruebas anteriores pasan junto a 23 nuevas |
| `npm.cmd run build` | TypeScript y Vite correctos, 184 módulos transformados |
| `npm.cmd run lint` | Correcto, salida 0 |
| Build backend | Compilación Java 21, JAR ejecutable y `verify` correctos |

Incidencia resuelta del entorno: la primera ejecución de Vitest dentro del sandbox aprobó 38 pruebas, pero falló al iniciar el worker de catálogo por timeout. Se repitió la suite completa fuera del sandbox, manteniendo un único worker de threads y sin modificar configuración ni dependencias; las 58 pruebas pasaron. No se contabiliza la primera ejecución incompleta como éxito. Maven mostró advertencias ya presentes por APIs obsoletas/autoanexado de Mockito y conexiones de contextos de prueba cuyos contenedores habían terminado; no hubo errores de pruebas.

| Comprobación integrada | Resultado |
|---|---|
| `docker compose up --build --wait --wait-timeout 240` | Salida 0; imágenes de backend y frontend reconstruidas; `db`, `backend` y `frontend` healthy |
| Flyway en PostgreSQL local | V1, V2 y V3 con `success=true`; sin migración nueva |
| Esquema/datos locales | 0 habitaciones; no existen tablas `reservations` ni `payments`; no se insertaron fixtures |
| `node scripts/smoke.mjs` | Salida 0: frontend, JS compilado, proxy, readiness/PostgreSQL, catálogo, disponibilidad, validación 400, inventario protegido, OpenAPI y Swagger |
| Navegador integrado | `/availability` cargó la aplicación compilada y mostró formulario, filtros, navegación y aviso de búsqueda orientativa |

El smoke real atraviesa `localhost:3000` → Nginx → Spring Boot → PostgreSQL. Comprueba búsqueda anónima válida con página y orden por precio, fechas iguales, ausencia de parámetros y cero huéspedes; también confirma HTTP 401 en recursos protegidos y el contrato de respuestas OpenAPI. La base de desarrollo vacía ejercita el resultado vacío; los resultados con habitaciones, paginación y cálculo monetario se verificaron con HTTP real en `AvailabilityIT` y su representación con Testing Library. No se presenta esta comprobación visual como una suite E2E de navegador de todos los formularios.

`AvailabilityIT` usa HTTP real y PostgreSQL 18.6 aislado mediante Testcontainers: elegibilidad, capacidad exacta y superior, estados excluidos, fechas y bisiestos, importes exactos, filtros y límites, páginas SQL, desempate por ID, acceso anónimo, protección administrativa, ausencia de tablas futuras y contrato OpenAPI. Los fixtures viven únicamente en la base del contenedor de pruebas.

`Availability.test.tsx` cubre formulario público, campos obligatorios, fechas iguales/invertidas/no reales, huéspedes inválidos, precios, filtros, reinicio de página, carga, errores/reintento, vacío, representación de moneda y estimaciones recibidas, nueva consulta con filtros iguales y ausencia de acción de reserva. Se ejecutan también las suites anteriores de identidad, catálogo y base ejecutable.

## Fuera de alcance

No se implementaron reservas, persistencia de reservas, idempotencia, antisolapamiento, anti-overbooking, holds, pagos, check-in, check-out, no-show ni otras funciones de Fase 5. No se alteraron commits ni ramas remotas. La próxima decisión corresponde al usuario tras revisar esta entrega. No quedan fallos funcionales ni decisiones bloqueantes pendientes en el alcance de Fase 4.

## Archivos de esta entrega

19 archivos: 11 nuevos y 8 modificados.

| Categoría | Nuevos | Modificados |
|---|---|---|
| Backend, API de `rooms` | `rooms/api/AvailabilityController.java`, `AvailabilityQuery.java`, `AvailabilityView.java` | — |
| Backend, consulta | `rooms/application/AvailabilityService.java`, `rooms/infrastructure/AvailabilitySpecifications.java` | — |
| Backend, integración | — | `identity/infrastructure/SecurityConfiguration.java`, `shared/api/SystemController.java`, `shared/config/OpenApiConfiguration.java` |
| Pruebas backend | `backend/src/test/java/com/portfolio/reservation/AvailabilityIT.java` | `FoundationIT.java` en la misma carpeta |
| Frontend | `frontend/src/features/rooms/AvailabilityPage.tsx`, `availability-api.ts`, `availability-schema.ts`, `Availability.test.tsx` | `frontend/src/app/App.tsx` |
| Documentación | `docs/fase-4.md` | `README.md`, `docs/plan-inicial.md` (solo estado de aprobación) |
| Smoke integrado | — | `scripts/smoke.mjs` |

Las rutas Java de producción de la tabla parten de `backend/src/main/java/com/portfolio/reservation/`. Los nombres abreviados de frontend pertenecen a la misma carpeta `features/rooms`. Las entidades, repositorios, servicios existentes del catálogo, migraciones, lógica de autenticación, dependencias y configuración Docker no se modifican.

## Revisión de secretos, artefactos y Git

La revisión de los 19 archivos contrastó también la contraseña y la clave JWT reales de `.env` sin imprimir sus valores: ninguna coincidencia. No se detectaron claves privadas, tokens reales, credenciales embebidas, logs, builds, dependencias generadas ni ficheros temporales entre los cambios. La clave de arranque del contenedor de JUnit se genera como fixture exclusivamente de prueba y no procede del entorno real.

`.env`, `.env.*`, `.pem`, `.key`, logs, `node_modules`, `dist` y `target` siguen ignorados. Los artefactos de verificación quedan únicamente en carpetas ignoradas. V1, V2 y V3 no tienen diferencias respecto de HEAD. No hay cambios de `AuthService` ni de otros servicios de identidad.

Base conservada: `HEAD`, `main` y `origin/main` apuntan a `28007099953fd774a6dd50a5fb6c85c31dd14311`; diferencia entre ramas `0 / 0`. Los 19 archivos se dejan en el working tree para revisión, con **staging vacío**. No se realizó staging, commit ni push.
