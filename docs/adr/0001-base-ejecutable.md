# ADR 0001 — Base ejecutable

Estado: adoptada para la Fase 1, dentro de la arquitectura y reglas de negocio aprobadas.

## Contexto

La primera entrega debe arrancar de forma reproducible, comprobar PostgreSQL y permitir iteraciones posteriores sin implementar identidad ni reservas. En el equipo inicial hay Node.js 24.12.0 y Java 8; el Java local no es suficiente para Spring Boot.

## Decisiones

1. Usar Java 21 LTS y Spring Boot 4.1.1. Ejecutar Java en contenedores permite trabajar sin modificar la instalación Java del equipo.
2. Mantener el monolito modular y el monorepositorio. Crear únicamente paquetes utilizados; los módulos de negocio llegarán en sus fases.
3. PostgreSQL 18.6, Flyway y validación del esquema con Hibernate. La migración V1 prepara `btree_gist`, sin entidades de negocio.
4. Separar liveness de readiness. Readiness exige conexión a PostgreSQL.
5. Nginx sirve React y reenvía API y documentación al backend, manteniendo un mismo origen.
6. React consulta la salud del sistema con timeout, cancelación y reintento manual. El cliente HTTP simple es suficiente para esta pantalla; el router, TanStack Query y formularios llegarán con funciones que los necesiten.
7. JUnit + Testcontainers verifican el arranque con PostgreSQL real. Las pruebas se ejecutan mediante `verify`, también disponible dentro de Docker.
8. Vitest verifica la interfaz ante éxito, fallo y recuperación. El smoke test comprueba los servicios construidos.
9. Maven Wrapper y lockfile npm registran las dependencias. Docker y CI utilizan Node.js 24.12.0.
10. Spring Security y JWT se incorporarán en Fase 2. Los únicos endpoints implementados son técnicos y los puertos del Compose quedan vinculados a localhost.

## Consecuencias

- Se necesita Docker activo para integración; no se sustituyen las garantías PostgreSQL por H2.
- Las pruebas en contenedor acceden al socket de Docker y utilizan una base efímera propia.
- El usuario local de PostgreSQL puede ejecutar las migraciones; el despliegue requerirá revisar cuentas y permisos.
- Los tags Java 21 pueden recibir actualizaciones. No se promete reproducibilidad bit a bit con tags mutables.
- El arranque inicial requiere red y tiempo para descargar dependencias.
- No se ha aprobado una plataforma de despliegue ni la creación de un remoto GitHub.

## Referencias oficiales consultadas

- [Requisitos de Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html).
- [Spring Initializr](https://start.spring.io/).
- [Compatibilidad y uso de springdoc](https://springdoc.org/).
- [Requisitos de Vite](https://vite.dev/guide/).
- [Testcontainers y Spring Boot](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).
- [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/).
- [PostgreSQL con Docker y ruta del volumen](https://docs.docker.com/guides/postgresql/).
