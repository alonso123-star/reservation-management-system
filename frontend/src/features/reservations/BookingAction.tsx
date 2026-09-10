import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import { ApiError, errorMessage, type User } from '../auth/api/auth'
import type { AvailabilityItem } from '../rooms/availability-api'
import type { AvailabilityForm } from '../rooms/availability-schema'
import { book, money, type BookingRequest, type Reservation } from './api'
import { CustomerPicker } from './CustomerPicker'

export function BookingAction({ room, filter, refresh }: { room: AvailabilityItem; filter: AvailabilityForm; refresh: () => void }) {
  const { user } = useAuth()
  return user ? <BookingForm key={user.id} room={room} filter={filter} refresh={refresh} user={user} /> : <Link to="/login">Inicia sesión para reservar</Link>
}
function BookingForm({ room, filter, refresh, user }: { room: AvailabilityItem; filter: AvailabilityForm; refresh: () => void; user: User }) {
  const cache = useQueryClient()
  const [open, setOpen] = useState(false)
  const [customer, setCustomer] = useState('')
  const [pending, setPending] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const sending = useRef(false)
  const attempt = useRef<{ key: string; body: BookingRequest; actor: string } | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<Reservation | null>(null)
  const staff = user.role !== 'CLIENTE'
  const unavailable = error instanceof ApiError && error.code === 'ROOM_NOT_AVAILABLE'
  if (result) return <div role="status"><p>Reserva confirmada: {result.code}</p>
    <p>Total acordado: {money(result.totalAmount, result.currency)}</p><Link to={'/reservations/' + result.id}>Ver reserva</Link></div>
  if (!open) return <button type="button" onClick={() => setOpen(true)}>{staff ? 'Reservar para cliente' : 'Reservar'}</button>
  return <div className="status-form" aria-label="Confirmar estancia">
    <h3>Revisa tu reserva</h3><p>{room.roomType.name} · {filter.checkIn} al {filter.checkOut}</p>
    <p>{room.nights} noches · {filter.guests} huéspedes · {money(room.roomType.basePrice, room.roomType.currency)} por noche</p>
    <p>Total estimado: {money(room.estimatedTotal, room.roomType.currency)}. La tarifa vigente se fija al confirmar.</p>
    {staff && !attempted && <CustomerPicker value={customer} onChange={setCustomer} />}
    {error != null && <p role="alert">{unavailable ? 'La habitación ya no está disponible para esas fechas. Actualiza la búsqueda.' : errorMessage(error)}</p>}
    {error != null && !unavailable && <p>Reintenta esta misma solicitud o consulta tu historial antes de iniciar otra reserva.</p>}
    {unavailable ? <button type="button" onClick={refresh}>Actualizar disponibilidad</button> :
      <button type="button" disabled={pending || (staff && !customer)} onClick={async () => {
        if (sending.current) return
        sending.current = true; setPending(true); setError(null); setAttempted(true)
        if (!attempt.current) attempt.current = { key: crypto.randomUUID(), actor: user.id,
          body: { roomId: room.id, checkIn: filter.checkIn, checkOut: filter.checkOut, guests: Number(filter.guests), ...(staff ? { customerId: customer } : {}) } }
        try {
          if (attempt.current.actor !== user.id) throw new Error('Session changed')
          const created = await book(attempt.current.body, attempt.current.key, staff)
          setResult(created)
          void cache.invalidateQueries({ queryKey: ['reservations'] })
          // Refresh on user action so the confirmation does not disappear with its room card.
          void cache.invalidateQueries({ queryKey: ['availability'], refetchType: 'none' })
        } catch (failure) { setError(failure) }
        finally { sending.current = false; setPending(false) }
      }}>{pending ? 'Confirmando…' : error != null ? 'Reintentar reserva' : 'Confirmar reserva'}</button>}
    <Link to="/reservations">Consultar mis reservas</Link>
  </div>
}
