import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth } from '../auth/useAuth'
import { api, errorMessage } from '../auth/api/auth'
import { history, money, statusLabels, type Reservation } from './api'
import { CustomerPicker } from './CustomerPicker'
import { PaymentPanel } from '../payments/PaymentPanel'

const cancelSchema = z.object({ reason: z.string().trim().min(1, 'Indica el motivo.').max(500, 'Máximo 500 caracteres.') })

function ReservationSummary({ value }: { value: Reservation }) {
  return <>
    <h2>{value.code}</h2><p>Habitación {value.roomCode} · {value.roomTypeName}</p>
    <p>{value.checkIn} al {value.checkOut} · {value.nights} noches · {value.guests} huéspedes</p>
    <p>{money(value.agreedNightlyRate, value.currency)} por noche · Total: {money(value.totalAmount, value.currency)} · {value.currency}</p>
    <p>Estado: {statusLabels[value.status]}</p>
    {value.cancellationReason && <p>Motivo: {value.cancellationReason}</p>}
  </>
}
export function ReservationHistory() {
  const { user } = useAuth()
  const staff = user?.role !== 'CLIENTE'
  const [filters, setFilters] = useState<Record<string, string>>({ size: '6', sort: 'checkIn,desc' })
  const [customer, setCustomer] = useState('')
  const [page, setPage] = useState(0)
  const result = useQuery({ queryKey: ['reservations', user?.id, filters, page], queryFn: () => history({ ...filters, page }), retry: false })
  return <section className="catalog-page">
    <h1>{staff ? 'Reservas del hotel' : 'Mis reservas'}</h1>
    <Link to="/availability">{staff ? 'Crear reserva para cliente' : 'Buscar otra estancia'}</Link>
    <form className="catalog-filters" onSubmit={event => {
      event.preventDefault()
      const data = Object.fromEntries(new FormData(event.currentTarget)) as Record<string, string>
      setFilters({ ...data, ...(staff ? { customerId: customer } : {}) }); setPage(0)
    }}>
      <label>Código de reserva<input name="code" maxLength={40} /></label>
      <label>Estado<select name="status"><option value="">Todos</option>
        {Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
      <label>Entrada desde<input type="date" name="checkInFrom" /></label>
      <label>Entrada hasta<input type="date" name="checkInTo" /></label>
      {staff && <CustomerPicker value={customer} onChange={setCustomer} />}
      <label>Orden<select name="sort" defaultValue="checkIn,desc"><option value="checkIn,desc">Entrada más reciente</option><option value="checkIn,asc">Próximas entradas</option><option value="createdAt,desc">Creación más reciente</option></select></label>
      <label>Por página<select name="size" defaultValue="6"><option>6</option><option>12</option><option>24</option></select></label>
      <button>Aplicar filtros</button>
    </form>
    {result.isPending && <p role="status">Cargando reservas…</p>}
    {result.error && <div role="alert"><p>{errorMessage(result.error)}</p><button onClick={() => void result.refetch()}>Reintentar</button></div>}
    {result.data && <>
      {result.data.items.length === 0 && <p className="catalog-empty">No hay reservas que coincidan.</p>}
      <div className="catalog-grid">{result.data.items.map(value => <article className="feature-card" key={value.id}>
        <ReservationSummary value={value} /><Link to={'/reservations/' + value.id}>Ver detalle de reserva</Link>
      </article>)}</div>
      <nav className="catalog-pagination" aria-label="Páginas de reservas">
        <button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>Anterior</button>
        <span>Página {result.data.page + 1} de {Math.max(1, result.data.totalPages)}</span>
        <button disabled={page + 1 >= result.data.totalPages || result.isFetching} onClick={() => setPage(page + 1)}>Siguiente</button>
      </nav>
    </>}
  </section>
}
function CancelForm({ reservation }: { reservation: Reservation }) {
  const cache = useQueryClient()
  const { user } = useAuth()
  const { register, handleSubmit, formState: { errors } } = useForm<z.infer<typeof cancelSchema>>({ resolver: zodResolver(cancelSchema) })
  const cancel = useMutation({ mutationFn: (reason: string) => api<Reservation>('/reservations/' + reservation.id + '/cancel', 'POST', { version: reservation.version, reason }),
    onSuccess: async value => {
      cache.setQueryData(['reservations', user?.id, 'detail', reservation.id], value)
      await Promise.all([cache.invalidateQueries({ queryKey: ['reservations'] }), cache.invalidateQueries({ queryKey: ['availability'] }),
        cache.invalidateQueries({ queryKey: ['payments', user?.id, reservation.id] })])
    } })
  return <form className="status-form" onSubmit={handleSubmit(value => cancel.mutate(value.reason))}>
    <label>Motivo de cancelación<textarea maxLength={500} {...register('reason')} /></label>
    {errors.reason && <p role="alert">{errors.reason.message}</p>}
    <p>Al confirmar se cancelará la reserva y se liberarán las fechas.</p>
    <p>Si tiene un pago aprobado, se generará automáticamente un reembolso simulado completo.</p>
    <button disabled={cancel.isPending}>{cancel.isPending ? 'Cancelando…' : 'Confirmar cancelación'}</button>
    {cancel.error && <div role="alert"><p>{errorMessage(cancel.error)}</p><button type="button" onClick={() => void cache.invalidateQueries({ queryKey: ['reservations'] })}>Actualizar reserva</button></div>}
  </form>
}
export function ReservationDetail() {
  const { id } = useParams()
  const { user } = useAuth()
  const result = useQuery({ queryKey: ['reservations', user?.id, 'detail', id], queryFn: () => api<Reservation>('/reservations/' + id), retry: false })
  return <section className="catalog-page"><h1>Detalle de reserva</h1><Link to="/reservations">Volver a reservas</Link>
    {result.isPending && <p role="status">Cargando reserva…</p>}
    {result.error && <p role="alert">{errorMessage(result.error)}</p>}
    {result.data && <article className="catalog-detail"><ReservationSummary value={result.data} />
      {user?.role !== 'CLIENTE' && <p>Cliente: {result.data.customerId} · Creada por: {result.data.createdBy}</p>}
      {result.data.canCancel && <CancelForm key={result.data.version} reservation={result.data} />}
      <PaymentPanel reservationId={result.data.id} />
    </article>}
  </section>
}
