import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import { errorMessage } from '../auth/api/auth'
import { history } from '../reservations/api'
import { ReservationIdentifier, ReservationSummary } from '../reservations/ReservationSummary'
import { RoleGuard } from '../rooms/RoleGuard'

export function ReceptionPage() {
  return <RoleGuard message="No tienes permiso para operar recepción."><ReceptionList /></RoleGuard>
}
function ReceptionList() {
  const { user } = useAuth()
  const [filters, setFilters] = useState({ status: 'CONFIRMED', code: '', checkInFrom: '', checkInTo: '' })
  const [page, setPage] = useState(0)
  const result = useQuery({ queryKey: ['reservations', user?.id, 'reception', filters, page], retry: false,
    queryFn: () => history({ ...filters, page, size: 6, sort: 'checkIn,asc' }) })
  return <section className="catalog-page"><h1>Recepción</h1>
    <p>Localiza una reserva y abre su detalle para comprobar el huésped, pago y acciones según la hora del hotel.</p>
    <nav className="catalog-pagination" aria-label="Vistas de recepción">
      <button onClick={() => { setFilters({ status: 'CONFIRMED', code: '', checkInFrom: '', checkInTo: '' }); setPage(0) }}>Llegadas y posibles no-show</button>
      <button onClick={() => { setFilters({ status: 'CHECKED_IN', code: '', checkInFrom: '', checkInTo: '' }); setPage(0) }}>Huéspedes alojados</button>
      <Link to="/reservations">Historial completo</Link>
    </nav>
    <form className="catalog-filters" onSubmit={event => {
      event.preventDefault(); const data = new FormData(event.currentTarget)
      setFilters({ ...filters, code: String(data.get('code')), checkInFrom: String(data.get('from')), checkInTo: String(data.get('to')) }); setPage(0)
    }} key={filters.status}>
      <label>Código de reserva<input name="code" maxLength={40} defaultValue={filters.code} /></label>
      <label>Llegada desde<input name="from" type="date" defaultValue={filters.checkInFrom} /></label>
      <label>Llegada hasta<input name="to" type="date" defaultValue={filters.checkInTo} /></label>
      <button>Buscar reservas</button>
    </form>
    <p>Vista: {filters.status === 'CHECKED_IN' ? 'Huéspedes alojados' : 'Reservas confirmadas por revisar'}</p>
    {result.isPending && <p role="status">Cargando recepción…</p>}
    {result.error && <div role="alert"><p>{errorMessage(result.error)}</p><button onClick={() => void result.refetch()}>Reintentar</button></div>}
    {result.data && <>
      {result.data.items.length === 0 && <p>No hay reservas en esta vista.</p>}
      <div className="catalog-grid reservation-grid">{result.data.items.map(r => <article key={r.id} className="reservation-card">
        <ReservationSummary value={r} />
        <p className="reservation-actors">Cliente: <ReservationIdentifier value={r.customerId} /></p>
        <Link to={'/reservations/' + r.id}>Consultar huésped, pago y acciones</Link>
      </article>)}</div>
      <nav className="catalog-pagination" aria-label="Páginas de recepción">
        <button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>Anterior</button>
        <span>Página {result.data.page + 1} de {Math.max(1, result.data.totalPages)}</span>
        <button disabled={page + 1 >= result.data.totalPages || result.isFetching} onClick={() => setPage(page + 1)}>Siguiente</button>
      </nav>
    </>}
  </section>
}
