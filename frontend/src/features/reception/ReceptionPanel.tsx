import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import { api, errorMessage } from '../auth/api/auth'
import { money, statusLabels, type Reservation } from '../reservations/api'

type Action = 'check-in' | 'check-out' | 'no-show'
const labels: Record<Action, string> = { 'check-in': 'Check-in', 'check-out': 'Check-out', 'no-show': 'No-show' }
export function ReceptionPanel({ reservation }: { reservation: Reservation }) {
  const { user } = useAuth()
  return user && user.role !== 'CLIENTE' && reservation.reception
    ? <Actions key={user.id + ':' + reservation.id + ':' + reservation.version} reservation={reservation} userId={user.id} /> : null
}
function Actions({ reservation: r, userId }: { reservation: Reservation; userId: string }) {
  const info = r.reception!
  const cache = useQueryClient()
  const [selected, setSelected] = useState<Action | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const change = useMutation({ mutationFn: (action: Action) => api<Reservation>('/reservations/' + r.id + '/' + action, 'POST', { version: r.version }),
    onSuccess: async value => {
      cache.setQueryData(['reservations', userId, 'detail', r.id], value)
      await Promise.all([cache.invalidateQueries({ queryKey: ['reservations'] }), cache.invalidateQueries({ queryKey: ['payments', userId, r.id] }),
        cache.invalidateQueries({ queryKey: ['availability'] })])
    } })
  const deadline = new Intl.DateTimeFormat('es-PE', { timeZone: info.hotelTimeZone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(info.arrivalDeadline))
  return <section aria-label="Recepción de la reserva">
    <h2>Recepción</h2>
    <p>Huésped: {info.customerName}</p>
    <p>Fecha del hotel: {info.hotelDate} · {info.hotelTimeZone}</p>
    <p>Pago para ingreso: {info.fullyPaid ? 'Completo y no reembolsado' : 'Sin pago completo vigente'}</p>
    <p>Plazo de llegada: {deadline} ({info.hotelTimeZone}). No-show solo después de este plazo.</p>
    {r.checkedInAt && <p>Ingreso registrado: {r.checkedInAt}</p>}
    {r.checkedOutAt && <p>Salida registrada: {r.checkedOutAt}</p>}
    {r.status === 'CONFIRMED' && !info.fullyPaid && <p>Completa el pago antes de registrar el check-in.</p>}
    {r.status === 'CONFIRMED' && info.fullyPaid && !info.canCheckIn && <p>El check-in está fuera del periodo permitido por el hotel.</p>}
    {change.error ? <div role="alert"><p>{errorMessage(change.error)}</p><p>Consulta el estado actual antes de volver a confirmar; una respuesta perdida puede haber completado la operación.</p></div> :
      (Object.entries({ 'check-in': info.canCheckIn, 'check-out': info.canCheckOut, 'no-show': info.canNoShow }) as [Action, boolean][])
        .filter(([, allowed]) => allowed).map(([action]) => <button key={action} type="button" disabled={change.isPending || refreshing} onClick={() => setSelected(action)}>{labels[action]}</button>)}
    <button type="button" disabled={change.isPending || refreshing} onClick={async () => {
      setRefreshing(true)
      try { await cache.invalidateQueries({ queryKey: ['reservations', userId, 'detail', r.id] }); change.reset(); setSelected(null) }
      finally { setRefreshing(false) }
    }}>Actualizar recepción</button>
    {selected && !change.error && <div role="group" aria-label="Confirmar operación de recepción">
      <h3>Confirmar {labels[selected]}</h3>
      <p>{r.code} · {info.customerName} · Habitación {r.roomCode} · {r.roomTypeName}</p>
      <p>{r.checkIn} al {r.checkOut} · {r.guests} huéspedes · {statusLabels[r.status]}</p>
      <p>{money(r.totalAmount, r.currency)} · Pago: {info.fullyPaid ? 'completo' : 'pendiente o reembolsado'}</p>
      {selected === 'check-out' && <p>La salida anticipada conserva las fechas y el importe. No libera noches ni genera reembolso.</p>}
      {selected === 'no-show' && <p>Se registrará la no presentación y se liberarán las fechas bloqueadas. Los pagos se conservan sin reembolso automático.</p>}
      <button type="button" disabled={change.isPending || refreshing} onClick={() => change.mutate(selected)}>{change.isPending ? 'Registrando…' : 'Confirmar operación'}</button>
      <button type="button" disabled={change.isPending} onClick={() => setSelected(null)}>Volver</button>
    </div>}
  </section>
}
