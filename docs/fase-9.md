# Fase 9 — Presentación

## Alcance y base

Entrega local autorizada sobre `6d93b6a31a0668b071c45959e36251e9971a984a`, cierre de Fase 8. Antes de modificar: HEAD, main y origin/main coincidían; 0 ahead / 0 behind, working tree limpio e índice vacío. Se leyó el plan completo y se mantuvo su arquitectura.

Esta fase prepara evaluación reproducible: Playwright real, demo sintética aislada, portada coherente con las capacidades implementadas, CI, Docker final y README. No añade reglas de negocio, endpoints de negocio ni migraciones. No se reimplementan fases anteriores. No se autoriza staging, commit, push, tags ni releases.

## Decisiones y datos demo

`scripts/sandbox.mjs` fija dos proyectos Compose independientes: rms-demo (3001/8081/5433, base rms_demo) y rms-e2e (3002/8082/5434, base rms_e2e). Conserva el Compose normal, sus datos y su configuración. Genera secretos PostgreSQL/JWT aleatorios en archivos ignorados .env.demo/.env.e2e, no los imprime y rechaza nombres/puertos/base/zona incompatibles. Los valores verificados prevalecen sobre variables de proceso para evitar que una configuración externa seleccione la base normal accidentalmente.

`scripts/demo.mjs` ofrece up, seed, status, stop, down y reset. Up construye y espera health; seed solo admite demo vacía. Stop/down conservan volumen; reset exige el comando explícito y elimina exclusivamente volúmenes del proyecto elegido. El reset de tablas utilizado por Playwright está limitado a rms_e2e, preserva roles y Flyway y se ejecuta antes de cada caso. No se ejecuta ningún seed desde Spring, producción o el Compose normal.

`scripts/demo-data.mjs` registra el primer administrador sintético, lo promueve antes de login exclusivamente en la base aislada y audita el bootstrap. Resto de usuarios, catálogo, reservas, pagos, cancelación y recepción se crean por API real con Origin, CSRF y JWT, usando las reglas existentes. Al terminar se cierran las sesiones del cargador. No se permite sembrar encima de datos existentes; una interrupción se recupera con reset explícito, no con borrado automático.

Tres cuentas @example.test (CLIENTE, EMPLEADO, ADMIN) con contraseña pública de demostración documentada en README; tres tipos Standard/Deluxe/Suite, tarifas PEN 120/220/350, capacidades 2/3/4. Ocho habitaciones: 101, 102, 103, 201, 202, 203, 301, 302, en pisos 1–3; seis operativas, 103 en mantenimiento, 203 fuera de servicio.

Cinco reservas de demo: 101 confirmada futura, 102 CHECKED_IN, 201 CHECKED_OUT anticipado, 202 CANCELLED con refund, 301 NO_SHOW. Seis intentos de pago, tres APPROVED, un refund. Fechas relativas al día del seed en America/Lima. Importe aprobado total PEN 1120, refund PEN 440, neto PEN 680 si el periodo incluye esos movimientos. No se mueve dinero real ni se solicitan tarjetas. Los datos persisten al reiniciar; para renovar fechas se recrea la demo explícitamente.

## Playwright

Configuración TypeScript, Playwright 1.63.0 con Chromium 153.0.8010.12, URL fija del entorno E2E, un worker, sin paralelismo entre casos ni retries automáticos. Vitest limita su inclusión a src/**/*.test.{ts,tsx}; los E2E tienen typecheck separado. El navegador usa idioma es-PE y zona America/Lima.

Fixtures sintéticos independientes, contexto nuevo por caso, base desechable reiniciada antes de cada uno. La preparación de datos usa API/SQL aislado; todas las acciones que se verifican utilizan la UI real contra el bundle compilado servido por Nginx, Spring y PostgreSQL. Sin mocks de red, access tokens fabricados ni storageState guardado. No ejecutar dos procesos Playwright simultáneamente sobre la misma base.

Ocho recorridos:

1. Registro, login, disponibilidad, creación, primer pago DECLINED, segundo APPROVED, EMPLEADO check-in/check-out y conservación del bloqueo del rango original sin refund.
2. Pago, exclusión de disponibilidad, historial/detalle, cancelación, un refund completo y disponibilidad restaurada.
3. Rutas privadas sin sesión y catálogo público con datos.
4. CLIENTE restringido frente a administración y recepción.
5. EMPLEADO restringido frente a administración, con acceso a recepción.
6. Recarga con refresh real, revocación de la sesión actual y logout.
7. Segundo cliente intenta abrir una reserva ajena: HTTP 404 y ausencia de detalle/pagos en UI.
8. ADMIN crea/consulta/cambia rol/activa/desactiva, comprueba revocación inmediata de otra sesión, último ADMIN protegido, dashboard exacto y auditoría filtrada con detalle seguro.

El panel se comprueba sobre [día del seed − 1, día del seed + 3): 5 reservas creadas, 2 llegadas, 2 salidas, 4/24 noches, neto PEN 680. El margen alrededor de medianoche evita que el corte de día fragmente movimientos creados durante un recorrido. Las pruebas JUnit existentes siguen verificando límites horarios con Clock controlado y carreras concurrentes reales; no se añade un reloj manipulable al runtime.

Reportes HTML y capturas de fallo permanecen en carpetas ignoradas; también se excluyen del contexto Docker. Traces y vídeos desactivados para no almacenar tráfico con credenciales. No se guardan cookies ni tokens en archivos versionables.

## Presentación y documentación

Portada actualizada: catálogo, disponibilidad, reservas, pagos simulados, recepción y administración; nuevo enlace a búsqueda. Texto de login y pie de página coherentes con el sistema terminado. Se conserva la ilustración CSS y la identidad visual, sin nuevas imágenes ni rediseño. Estados vacíos existentes se mantienen claros y el seed permite evaluar pantallas con datos.

SystemController y OpenAPI anuncian 0.9.0 / fase 9; FoundationIT y smoke ajustan esa expectativa. El smoke comprueba rutas de todas las fases y separación de esquemas de creación de usuario/reserva. README documenta arranque desde clon, requisitos, cuentas, aislamiento, estructura, arquitectura, modelo, roles, reglas, variables, URLs, seguridad, concurrencia, idempotencia, pruebas, limpieza y límites. El plan solo actualiza su estado; informes históricos intactos.

## Verificaciones registradas

| Verificación | Evidencia / resultado |
|---|---|
| Backend completo | 200 pruebas, 0 fallos, 0 errores, 0 omitidas; Maven verify BUILD SUCCESS, 12 minutos |
| Clases backend | Administration 25, Authentication 17, Availability 37, Catalog 18, Foundation 4, Payment 25, Reception 43, Reservation 31 |
| Flyway en JUnit | V1–V6 desde cero; upgrade V5 → V6 con evento histórico preservado y changes={} validado |
| Frontend | 128 casos verificados: primera suite 125 aprobados y 3 errores de fixture de portada; tras adaptar el fixture, esos 3 aprobados |
| Build frontend / lint | TypeScript/Vite y ESLint correctos |
| Typecheck E2E | Correcto; scripts Node validan sintaxis |
| Docker | Construcción explícita --no-cache de ambas imágenes correcta; npm ci y Maven Wrapper dentro de imágenes, sin dependencias locales ocultas |
| Compose desde cero | rms-e2e con volumen nuevo: PostgreSQL, backend y frontend healthy; V1–V6 aplicadas y success=true |
| Playwright | 8 aprobadas al primer intento en 1.3 minutos; 0 fallos, sin retries ni mocks |
| Demo | Arranque desde cero, seed completo y reinicio down/up preservando 3 usuarios, 3 tipos, 8 habitaciones, 5 reservas, 6 pagos y 1 refund |
| Guardas de aislamiento | Seed repetido rechazado sin cambios; destino production rechazado; variable POSTGRES_DB heredada no consigue apuntar a la base local |
| Smoke HTTP | Técnico sobre E2E, aplicación local reconstruida y demo reiniciada aprobado; OpenAPI 0.9.0 y Swagger, rutas de todas las fases y DTOs de usuarios/reservas separados |
| Datos finales demo | Un ejemplar de cada estado de reserva esperado, APPROVED PEN 1120, refunds PEN 440, 47 eventos de auditoría y cero sesiones activas del seed |
| Presentación visual | Portada inspeccionada en Chromium a 1440px y 390px; conserva identidad visual, CTA legible y sin desbordamiento horizontal móvil; capturas locales solo en .tools ignorado |
| Regresiones | Autenticación, sesiones, catálogo, disponibilidad, reservas, concurrencia, GiST, idempotencia, pagos/refunds, recepción, administración, auditoría y dashboard verificados por JUnit y/o recorridos UI reales |

El nuevo Link de portada requiere Router. Sus tres pruebas de conectividad renderizaban Home directamente: se añadió MemoryRouter al fixture, manteniendo los casos y sus aserciones de salud. No fue un defecto previo de negocio. Las otras 125 pruebas no se repitieron, porque el único cambio posterior fue ese wrapper en el archivo de prueba. No se presenta la primera ejecución como una suite verde.

Backend mantiene avisos existentes de API Jackson deprecada y Mockito; los escenarios de fallo de auditoría y conexiones cerradas al destruir bases efímeras generan mensajes esperados. El resultado agregado de Failsafe es 200/0/0/0. No se tocaron esos servicios ni se suprimieron pruebas.

## Archivos de esta entrega

25 archivos: 16 modificados y 9 nuevos, sin staging.

| Categoría | Nuevos | Modificados |
|---|---|---|
| Demo y fixtures | scripts/sandbox.mjs, scripts/demo-data.mjs, scripts/demo.mjs | scripts/smoke.mjs |
| Playwright | frontend/playwright.config.ts, frontend/tsconfig.e2e.json, frontend/e2e/fixtures.ts, frontend/e2e/journeys.spec.ts, frontend/e2e/administration.spec.ts | frontend/package.json, frontend/package-lock.json, frontend/vite.config.ts, frontend/eslint.config.js |
| Presentación React | — | frontend/src/app/Home.tsx, App.tsx, App.test.tsx; frontend/src/features/auth/AuthPages.tsx |
| Metadatos backend | — | shared/api/SystemController.java, shared/config/OpenApiConfiguration.java; FoundationIT.java |
| Docker y CI | — | .dockerignore, .github/workflows/ci.yml |
| Documentación | docs/fase-9.md | README.md, docs/plan-inicial.md |

Playwright es dependencia de desarrollo fijada; el lockfile incorpora su árbol y npm elimina dos entradas opcionales huérfanas. No se actualizan dependencias de negocio. La instalación npm, incluida npm ci en Linux dentro de Docker, informa cero vulnerabilidades conocidas en ese momento.

## Revisión final y límites

Se revisaron los 25 archivos contra valores secretos locales sin imprimirlos y patrones de JWT, tokens y claves privadas. No aparecen secretos reales, datos personales reales ni artefactos accidentales. La contraseña demo es pública, sintética y restringida a las bases aisladas. .env, .env.demo, .env.e2e, claves, node_modules, target, dist, coverage y reportes permanecen ignorados. Sin espacios finales, conflictos ni errores de git diff --check. Staging vacío.

V1–V6 permanecen intactas; sin V7. No hay cambios en servicios, repositorios, DTOs o seguridad de negocio, SQL de producción, transiciones, permisos, GiST, idempotencia, cálculos ni reglas de fases anteriores. No se encontró un bug previo que requiriera modificar producción: solo se adaptó el fixture de portada al enlace nuevo. Los archivos de informes Fases 1–8 permanecen intactos.

El entorno local normal se restauró con imágenes reconstruidas y servicios healthy, conservando su usuario y cero habitaciones/reservas/pagos/refunds preexistentes. La demo queda disponible en 3001. E2E se detuvo después de pasar las pruebas, conservando su volumen aislado; `e2e up` permite reejecutarlo. No se borraron volúmenes existentes del usuario.

HEAD = main = origin/main = rama remota real: `6d93b6a31a0668b071c45959e36251e9971a984a`; 0 ahead / 0 behind. Working tree: 25 cambios de Fase 9 (16 modificados, 9 nuevos), índice vacío. Sin staging, commit, push, tags ni releases. Entrega lista para revisión local; publicación pendiente de autorización.

El README contiene el recorrido completo de evaluación desde un clon y se comprobaron sus comandos principales sobre un entorno nuevo. La versión remota todavía es Fase 8: otra persona dispondrá de estos nuevos comandos al recibir el working tree o cuando se autorice publicar Fase 9. No se afirma que un clon actual de origin incluya trabajo no publicado.

Compose es local, no producción. Chromium es la cobertura de navegador actual; sin Firefox/WebKit, auditoría formal de accesibilidad, correo, recuperación de contraseña, pagos reales, multi-hotel o despliegue público. Demo requiere Node + Docker e Internet en primera instalación. Los scripts históricos HTTP siguen limitados a Compose local en 3000 y no deben apuntarse a producción. La CI remota de esta entrega no puede ejecutarse antes de autorizar su publicación.

Las cookies de localhost no se aíslan por puerto. Para usar demo y entorno propio simultáneamente deben emplearse perfiles/ventana privada separados, como indica README. Las bases y claves sí están aisladas: una sesión de otro entorno no obtiene acceso. Playwright ya emplea contextos independientes. Se documenta este límite de evaluación local sin cambiar la seguridad aprobada.
