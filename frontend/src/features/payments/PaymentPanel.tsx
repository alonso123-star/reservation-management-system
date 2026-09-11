import { useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import { ApiError, errorMessage } from '../auth/api/auth'
import { money } from '../reservations/api'
import { pay, paymentHistory, type Payment } from './api'

const settlementLabels = { UNPAID: 'Sin pago aprobado', PAID: 'Pagada', REFUNDED: 'Reembolsada' }
export function PaymentPanel({ reservationId }: { reservationId: string }) {
  const { user } = useAuth()
  return user ? <PaymentContent key={user.id + ':' + reservationId} reservationId={reservationId} userId={user.id} /> : null
}
function PaymentContent({ reservationId, userId }: { reservationId: string; userId: string }) {
  const cache = useQueryClient()
  const [page, setPage] = useState(0)
  const [pending, setPending] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<Payment | null>(null)
  const key = useRef<string | null>(null)
  const sending = useRef(false)
  const history = useQuery({ queryKey: ['payments', userId, reservationId, page], queryFn: () => paymentHistory(reservationId, page), retry: false })
  const conflict = error instanceof ApiError && error.status === 409
  async function attempt() {
    if (sending.current) return
    sending.current = true; setPending(true); setError(null)
    if (!key.current) key.current = crypto.randomUUID()
    try {
      const response = await pay(reservationId, key.current)
      setResult(response); setUncertain(false); key.current = null
      setPage(0)
      void cache.invalidateQueries({ queryKey: ['payments', userId, reservationId] })
    } catch (failure) { setError(failure); setUncertain(true) }
    finally { sending.current = false; setPending(false) }
  }
  return <section aria-label="Pagos simulados">
    <h2>Pagos simulados</h2>
    <p>No se mueve dinero real. El primer intento nuevo se rechaza; el siguiente se aprueba. Reintentar una solicitud conserva su resultado.</p>
    {history.isPending && <p role="status">Cargando pagos…</p>}
    {history.error && <div role="alert"><p>{errorMessage(history.error)}</p><button type="button" onClick={() => void history.refetch()}>Reintentar consulta de pagos</button></div>}
    {history.data && <>
      <p>Estado de pago: {settlementLabels[history.data.settlement]}</p>
      <p>Total pendiente: {money(history.data.amountDue, history.data.currency)} · {history.data.currency}</p>
      {result && <p role="status">Intento {result.result === 'APPROVED' ? 'aprobado' : 'rechazado'}: {result.simulatedReference}</p>}
      {error != null && <p role="alert">{errorMessage(error)}</p>}
      {uncertain && !conflict && <p>Reintenta esta misma solicitud. Si sales de esta pantalla, consulta el historial antes de iniciar un pago nuevo.</p>}
      {conflict ? <button type="button" onClick={() => void history.refetch()}>Actualizar pagos</button> :
        (history.data.canPay || uncertain) && result?.result !== 'APPROVED' && <button type="button" disabled={pending || history.isFetching} onClick={() => void attempt()}>
          {pending ? 'Procesando pago…' : uncertain ? 'Reintentar mismo pago' : result?.result === 'DECLINED' ? 'Nuevo intento simulado' : 'Pagar importe completo (simulado)'}
        </button>}
      <h3>Historial de intentos</h3>
      {history.data.attempts.items.length === 0 && <p>No hay intentos de pago.</p>}
      {history.data.attempts.items.map(payment => <article className="feature-card" key={payment.id}>
        <p>{payment.result === 'APPROVED' ? 'Aprobado' : 'Rechazado'} · {money(payment.amount, payment.currency)}</p>
        <p>Referencia: {payment.simulatedReference}</p><p>Fecha: {payment.createdAt}</p>
        {payment.refund && <div><p>Reembolso completo: {money(payment.refund.amount, payment.refund.currency)}</p>
          <p>Motivo: {payment.refund.reason}</p><p>Fecha de reembolso: {payment.refund.createdAt}</p></div>}
      </article>)}
      <nav className="catalog-pagination" aria-label="Páginas de pagos">
        <button type="button" disabled={page === 0 || history.isFetching} onClick={() => setPage(page - 1)}>Pagos anteriores</button>
        <span>Página {history.data.attempts.page + 1} de {Math.max(1, history.data.attempts.totalPages)}</span>
        <button type="button" disabled={page + 1 >= history.data.attempts.totalPages || history.isFetching} onClick={() => setPage(page + 1)}>Más pagos</button>
      </nav>
    </>}
  </section>
}
