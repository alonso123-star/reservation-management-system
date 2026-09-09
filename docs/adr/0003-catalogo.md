# ADR 0003 — Catálogo del hotel

Estado: implementado localmente para revisión de Fase 3; sin autorización de commit ni push.

## Contexto

Las Fases 1 y 2 están en el commit 33f3540. Se autoriza únicamente catálogo. Se conserva el monolito modular, la infraestructura de identidad, errores, auditoría, Flyway, React y Docker. La última migración existente y aplicada antes de esta entrega era V2.

## Decisiones

- Módulo `rooms` con capas api/application/domain/infrastructure. Dos entidades RoomType y Room, repositorios JPA, un servicio concreto y DTOs explícitos. No se añade una arquitectura ni infraestructura de autenticación paralelas.
- V3 crea únicamente room_types y rooms, restricciones e índices. No modifica V1/V2. V1 ya instalaba btree_gist: se conserva como historia previa, sin añadir extensión ni usarla en catálogo.
- UUID, timestamps UTC, actividad y `@Version` en ambos recursos. La versión de tipo también evita perder cambios de tarifa o capacidad. FK sin cascada de borrado. Desactivación mediante PATCH.
- Precio positivo BigDecimal/NUMERIC(12,2). Moneda única configurable (PEN por defecto). Sin aritmética de precios en React; los formularios envían texto decimal. No se introduce cálculo de estancias, pagos o snapshots de reserva.
- Capacidad 1–1000 y pisos -5–200 como límites explícitos de validación. Código 30, nombre 100, descripción 2000 caracteres. Nombre único ignorando mayúsculas; código canónico en mayúsculas. Son límites de esta entrega, revisables antes de aceptarla.
- Actividad y estado operativo son independientes. Público: tipos activos; habitaciones activas, operativas y con tipo activo. Desactivar tipo oculta sus habitaciones sin mutarlas. ADMIN puede asociar tipos inactivos en inventario.
- GET públicos usan DTO mínimo; GET `/staff/...` exige ADMIN/EMPLEADO y expone versión para edición. ADMIN administra; EMPLEADO solo modifica estado operativo con un DTO restringido. El rol se valida en backend; no se admite desde el navegador.
- PATCH exige versión; null/omisión conservan el campo. Se verifica la versión recibida y el bloqueo optimista JPA comprueba la versión al escribir. La restricción UNIQUE arbitra inserciones simultáneas. 409 permite recarga consciente, sin sobrescritura automática.
- Specifications y Pageable ejecutan filtros y páginas en base de datos. Orden permitido con UUID de desempate. El ManyToOne se carga con EntityGraph en listados de habitaciones para evitar N+1. Búsquedas por subcadena no tienen índice especializado: suficiente para el catálogo inicial; evaluar EXPLAIN y volumen antes de ampliar índices.
- Se reutiliza AuditService en la transacción de escritura. No hay pantalla de auditoría ni almacén nuevo.
- Se conserva la seguridad de Fase 2: Origin en todas las escrituras, CSRF en operaciones con cookies, JWT en catálogo protegido. Resource Server exceptúa Bearer explícito del filtro CSRF; el frontend sigue enviando protección también en esas llamadas. Se aclara esta semántica en la documentación: [código oficial de Spring Security](https://github.com/spring-projects/spring-security/blob/main/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/OAuth2ResourceServerConfigurer.java).
- Pruebas HTTP con PostgreSQL real y transacciones concurrentes; componentes React con Vitest/Testing Library. No se crean usuarios privilegiados por defecto ni fixtures en Flyway.

## Consecuencias y límites

La tarifa base y ACTIVE describen el catálogo. No indican que una habitación esté libre para una fecha. No hay tablas, servicios, endpoints ni pruebas de disponibilidad, reservas, idempotencia o anti-overbooking de Fases 4/5. El catálogo no depende de ellas.

Las páginas por offset son estables ante empates; altas/bajas simultáneas entre distintas peticiones pueden desplazar resultados. No se promete una fotografía consistente entre páginas. No se oculta este comportamiento con descargas completas en React.

El inventario requiere usuarios ADMIN/EMPLEADO existentes. La gestión/provisión de roles permanece en su fase planificada; no se introduce un mecanismo público para elevar permisos.
