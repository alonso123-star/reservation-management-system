import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import { errorMessage } from '../auth/api/auth'
import { AdminLayout, Pagination } from './AdminLayout'
import { query, type AuditEvent, type Dashboard, type Page } from './api'

export function DashboardPage() { return <AdminLayout><DashboardContent /></AdminLayout> }
function DashboardContent() {
  const { user } = useAuth()
  const [period, setPeriod] = useState<{ from: string; to: string } | null>(null)
  const [validation, setValidation] = useState('')
  const result = useQuery({ queryKey: ['admin-dashboard', user?.id, period], enabled: period !== null, retry: false,
    queryFn: () => query<Dashboard>('/admin/dashboard', period!) })
  const d = result.data
  const money = (value: number) => new Intl.NumberFormat('es-PE', { style: 'currency', currency: d!.currency }).format(value)
  return <><h2>Panel administrativo</h2><p>Elige fechas del hotel. Entrada incluida y fin excluido; máximo 366 noches.</p>
    <form className="catalog-filters" onSubmit={e => { e.preventDefault(); const f = new FormData(e.currentTarget), from = String(f.get('from')), to = String(f.get('to'))
      if (from >= to) { setValidation('El inicio debe ser anterior al fin.'); return }
      setValidation(''); setPeriod({ from, to })
    }}><label>Desde<input type="date" name="from" required /></label><label>Hasta (excluido)<input type="date" name="to" required /></label><button>Consultar métricas</button></form>
    {validation && <p role="alert">{validation}</p>}
    {period && result.isPending && <p role="status">Cargando métricas…</p>}
    {result.error && <p role="alert">{errorMessage(result.error)} <button onClick={() => void result.refetch()}>Reintentar</button></p>}
    {d && <><p>Periodo: {d.from} al {d.to} (fin excluido) · {d.hotelTimeZone} · {d.currency}</p>
      <div className="catalog-grid"><article className="feature-card"><h3>Reservas creadas</h3><p>{d.reservationsCreated}</p><p>Fecha de creación dentro del periodo, todos los estados.</p></article>
        <article className="feature-card"><h3>Llegadas programadas</h3><p>{d.arrivals}</p><p>Fecha reservada de entrada; excluye canceladas y no-show.</p></article>
        <article className="feature-card"><h3>Salidas programadas</h3><p>{d.departures}</p><p>Fecha reservada de salida; excluye canceladas y no-show.</p></article>
        <article className="feature-card"><h3>Ocupación reservada</h3><p>{d.occupancyPercent === null ? 'Sin inventario operativo' : d.occupancyPercent + ' %'}</p><p>{d.roomNightsOccupied} / {d.roomNightsAvailable} noches de habitación · {d.eligibleRooms} habitaciones</p>
          <p>Reservas bloqueantes sobre inventario actualmente activo y operativo. Incluye CONFIRMED impagadas y CHECKED_OUT; no reconstruye presencia ni inventario histórico.</p></article>
        <article className="feature-card"><h3>Ingreso simulado neto</h3><p>{money(d.netRevenue)}</p><p>Aprobados: {money(d.approvedPayments)} · Reembolsos: {money(d.refunds)}</p><p>Cada movimiento por su propia fecha. DECLINED excluidos; el neto puede ser negativo.</p></article></div></>}
  </>
}
export function AuditPage() { return <AdminLayout><AuditContent /></AdminLayout> }
function AuditContent() {
  const { user } = useAuth()
  const [filters, setFilters] = useState<Record<string, string>>({})
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<AuditEvent | null>(null)
  const result = useQuery({ queryKey: ['admin-audit', user?.id, filters, page], retry: false,
    queryFn: () => query<Page<AuditEvent>>('/admin/audit-events', { ...filters, page, size: 10, sort: 'occurredAt,desc' }) })
  return <><h2>Auditoría</h2><form className="catalog-filters" onSubmit={e => {
    e.preventDefault(); const values = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>
    for (const key of ['from', 'to']) if (values[key]) values[key] += ':00Z'
    setFilters(values); setPage(0); setSelected(null)
  }}>
    <label>Actor UUID<input name="actorId" /></label><label>Acción<input name="action" maxLength={50} /></label><label>Recurso<input name="resource" maxLength={30} /></label>
    <label>ID del recurso<input name="resourceId" /></label><label>Request ID<input name="requestId" /></label>
    <label>Desde (UTC)<input name="from" type="datetime-local" /></label><label>Hasta excluido (UTC)<input name="to" type="datetime-local" /></label><button>Filtrar auditoría</button>
  </form>
    {result.isPending && <p role="status">Cargando auditoría…</p>}
    {result.error && <p role="alert">{errorMessage(result.error)} <button onClick={() => void result.refetch()}>Reintentar</button></p>}
    {result.data && <>{result.data.items.length === 0 && <p>No hay eventos con estos filtros.</p>}
      <div className="catalog-grid">{result.data.items.map(event => <article className="feature-card" key={event.id}><h3>{event.action}</h3><p>{event.occurredAt} · {event.resource}</p><p>Actor: {event.actorId ?? 'Sistema / anónimo'}</p><button onClick={() => setSelected(event)}>Ver evento {event.id}</button></article>)}</div>
      <Pagination page={page} totalPages={result.data.totalPages} busy={result.isFetching} onPage={p => { setPage(p); setSelected(null) }} /></>}
    {selected && <section aria-label="Detalle seguro de auditoría"><h3>{selected.action}</h3><p>{selected.resource}: {selected.resourceId ?? 'Sin identificador'}</p><p>Request ID: {selected.requestId}</p>
      <dl>{Object.entries(selected.changes).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl>
      {Object.keys(selected.changes).length === 0 && <p>Sin cambios de campos registrados.</p>}<button onClick={() => setSelected(null)}>Cerrar evento</button></section>}
  </>
}
