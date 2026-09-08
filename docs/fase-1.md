# Fase 1 — Base ejecutable

## Estado

**Completada y verificada localmente el 8 de septiembre de 2026.**

La Fase 0, la arquitectura y las reglas de negocio fueron aprobadas por el usuario. Esta entrega se limita a la Fase 1. **La Fase 2 no se ha iniciado.**

## Entregables

| Área | Resultado |
|---|---|
| Frontend | React + TypeScript + Vite, pantalla inicial adaptable, comprobación real de readiness y reintento manual. |
| Backend | Spring Boot 4.1.1 con Java 21, Maven Wrapper, JPA, Actuator y endpoint técnico de información. |
| PostgreSQL | Imagen 18.6, credenciales locales externas a Git y volumen persistente. |
| Flyway | Migración V1 que instala `btree_gist`; Hibernate valida el esquema. |
| Documentación API | OpenAPI y Swagger UI para el endpoint técnico. |
| Docker | Construcciones multietapa, backend y Nginx con usuarios sin privilegios, dependencias con healthchecks y puertos limitados a localhost. |
| Testing | Cuatro pruebas JUnit de integración con PostgreSQL real y tres pruebas de interfaz con Vitest. |
| CI | Jobs de frontend, backend y Compose con smoke test para GitHub Actions. |
| Git | Repositorio local inicializado en `main`, exclusión de secretos y artefactos; sin remoto ni publicación. |
| Documentación | README de arranque, ADR de decisiones, estado de aprobación del plan e informe de esta fase. |

La única tabla de aplicación presente en el esquema público es el historial técnico `flyway_schema_history`. No se han creado usuarios, roles, habitaciones, reservas, pagos ni sesiones.

## Comprobaciones realizadas

| Comprobación | Resultado |
|---|---|
| `docker compose config --quiet` | Configuración válida con el archivo local `.env`. |
| `docker compose --profile test run --rm backend-tests` | Maven `BUILD SUCCESS`; 4 pruebas, 0 fallos, 0 errores y 0 omitidas. |
| Migración sobre base vacía | V1 aplicada, extensión `btree_gist` presente y sin tablas de negocio. |
| Backend con PostgreSQL | Arranque de Spring, JPA y conexión JDBC correctos. |
| API técnica y OpenAPI | Respuestas HTTP correctas y contrato del endpoint de información presente. |
| Límites de fase | Rutas futuras de reservas y login no implementadas, respuesta 404. |
| `npm.cmd run lint` | ESLint 10 sin errores. |
| `npm.cmd test` | 3 pruebas aprobadas: éxito, fallo con reintento y respuesta inesperada. |
| `npm.cmd run build` | TypeScript y construcción Vite aprobados. |
| Instalación npm final | 0 vulnerabilidades reportadas por npm en la auditoría de instalación. |
| Construcción Docker con lockfile final | Imágenes de frontend y backend construidas correctamente; `npm ci` también funciona en Linux. |
| `docker compose up --build --wait --wait-timeout 240` | Los tres servicios quedaron saludables. |
| `node scripts/smoke.mjs` | Frontend, JavaScript compilado, proxy, readiness con PostgreSQL, API, OpenAPI y Swagger correctos. |
| Navegador | Pantalla renderizada y estado “Servicios disponibles”; botón de comprobación probado. |
| Caída controlada de PostgreSQL | Readiness devuelve HTTP 503 / DOWN; liveness permanece HTTP 200 / UP. |
| Recuperación | Tras esperar la salud de todos los servicios, el smoke test volvió a pasar sin reconstruir la aplicación. |
| Persistencia de la migración | Después del reinicio de PostgreSQL, el historial conserva una única V1 aplicada correctamente. |
| Exclusiones Git | `.env`, dependencias, builds, caché TypeScript, resultados Maven y herramientas temporales quedan ignorados. |

Las pruebas JUnit se ejecutaron con Testcontainers 2.0.5 y JUnit Jupiter 6.0.3, versiones gestionadas por Spring Boot. El contenedor de pruebas utilizó PostgreSQL efímero independiente del volumen de desarrollo.

## Incidencias y decisiones resueltas

- **Java local 8:** insuficiente para esta versión de Spring Boot. Se ejecutó Java 21 dentro de Docker sin cambiar la instalación Java del equipo.
- **Docker apagado:** se inició Docker Desktop y se verificó su motor Linux.
- **Compatibilidad TypeScript/ESLint:** TypeScript 7 no era compatible con el rango declarado por typescript-eslint. Se fijó TypeScript 5.9.3 y se verificó con ESLint 10.10.0, sin forzar dependencias.
- **Vitest en el sandbox:** los procesos de prueba no lograron iniciarse dentro del entorno restringido. La ejecución autorizada fuera del sandbox completó las tres pruebas.
- **Secuencia de recuperación:** el primer intento coincidió con la recreación del backend durante la construcción final y se repitió una vez terminada. Después de restaurar PostgreSQL, el pool JDBC necesitó recuperarse; esperar únicamente la salud de la base no garantiza la del backend. Se esperó la salud de los tres servicios y el smoke test pasó.
- **Módulos futuros:** router, consultas de negocio, formularios, Spring Security y JWT se incorporarán cuando corresponda. Esta base no simula funcionalidades todavía inexistentes.
- **Configuración local:** se generó `.env` con una contraseña aleatoria de PostgreSQL, ignorada por Git. `.env.example` contiene únicamente una plantilla.

## Cómo revisar la entrega

Con Docker Desktop activo:

```powershell
docker compose up --build --wait --wait-timeout 240
node scripts/smoke.mjs
```

Aplicación: http://localhost:3000

Swagger: http://localhost:3000/swagger-ui/index.html

Las instrucciones completas, las pruebas del backend y el desarrollo con recarga están en [README](../README.md). Las decisiones están en [ADR 0001](adr/0001-base-ejecutable.md).

## Pendientes y autorización para continuar

- **Fase 2:** requiere autorización expresa. No hay registro, login, roles, JWT ni sesiones implementados.
- **CI remota:** el workflow está creado y sus comandos principales se verificaron localmente, pero no se ha ejecutado en GitHub porque no hay remoto configurado. Crear o publicar un repositorio remoto queda pendiente de una solicitud del usuario.
- **Desarrollo Java fuera de Docker:** opcionalmente requerirá instalar un JDK 21. No es necesario para utilizar ni probar esta base mediante Docker.
- **Producción:** la plataforma de despliegue, HTTPS y la separación de usuarios de migración/ejecución se decidirán más adelante. Esta entrega es local.

No quedan bloqueos conocidos para usar y verificar la Fase 1 con Docker. Los servicios se dejan ejecutándose localmente; `docker compose down` los detiene y conserva los datos.
