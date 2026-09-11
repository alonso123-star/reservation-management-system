import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { ApiError, api, type User } from '../auth/api/auth'
import { PaymentPanel } from './PaymentPanel'
import { ReservationDetail } from '../reservations/ReservationPages'
import type { Payment, PaymentHistory } from './api'

const auth = vi.hoisted(() => ({ user: null as User | null, ready: true }))
vi.mock('../auth/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../auth/api/auth', async original => ({ ...await original<typeof import('../auth/api/auth')>(), api: vi.fn() }))
const user: User = { id: 'synthetic-client', name: 'Cliente', email: 'client@example.test', role: 'CLIENTE', createdAt: '' }
const payment: Payment = { id: 'synthetic-payment', reservationId: 'reservation', amount: 251, currency: 'PEN', result: 'APPROVED', simulatedReference: 'SIM-P-FIXTURE', actorId: user.id, createdAt: '2030-01-01T12:00:00Z', refund: null }
const summary = (items: Payment[] = [], settlement: PaymentHistory['settlement'] = 'UNPAID', page = 0, totalPages = 1): PaymentHistory => ({
  settlement, amountDue: settlement === 'UNPAID' ? 251 : 0, currency: 'PEN', canPay: settlement === 'UNPAID',
  attempts: { items, page, size: 6, totalElements: items.length, totalPages },
})
const refunded = { ...payment, refund: { id: 'synthetic-refund', paymentId: payment.id, amount: 251, currency: 'PEN', actorId: user.id, reason: 'Cambio de planes', createdAt: '2030-01-02T12:00:00Z' } }
function view(element: ReactNode = <PaymentPanel reservationId="reservation" />, route = '/') {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  render(<QueryClientProvider client={cache}><MemoryRouter initialEntries={[route]}>{element}</MemoryRouter></QueryClientProvider>)
  return cache
}
const clickPay = async () => fireEvent.click(await screen.findByRole('button', { name: 'Pagar importe completo (simulado)' }))
beforeEach(() => { vi.resetAllMocks(); auth.user = user; vi.mocked(api).mockResolvedValue(summary()) })

it('shows server amount, currency and empty history without any card fields', async () => {
  view(); await screen.findByText('Estado de pago: Sin pago aprobado')
  expect(screen.getByText(/Total pendiente:.*251.00.*PEN/)).toBeInTheDocument()
  expect(screen.getByText('No hay intentos de pago.')).toBeInTheDocument()
  expect(screen.queryAllByRole('textbox')).toHaveLength(0)
  expect(screen.queryByRole('button', { name: /reembolsar/i })).not.toBeInTheDocument()
})
it('creates a payment with empty body and an idempotency key, showing approval', async () => {
  let paid = false
  vi.mocked(api).mockImplementation(async (_path, method) => {
    if (method === 'POST') { paid = true; return payment }
    return paid ? summary([payment], 'PAID') : summary()
  })
  view(); await clickPay(); await screen.findByText('Intento aprobado: SIM-P-FIXTURE')
  expect(api).toHaveBeenCalledWith('/reservations/reservation/payments', 'POST', {}, expect.any(String))
  await screen.findByText('Estado de pago: Pagada')
  expect(screen.queryByRole('button', { name: 'Pagar importe completo (simulado)' })).not.toBeInTheDocument()
})
it('disables the action while payment is pending', async () => {
  vi.mocked(api).mockImplementation(async (_path, method) => method === 'POST' ? new Promise(() => {}) : summary())
  view(); await clickPay(); expect(await screen.findByRole('button', { name: 'Procesando pago…' })).toBeDisabled()
  expect(vi.mocked(api).mock.calls.filter(c => c[1] === 'POST')).toHaveLength(1)
})
it('records a decline and uses a different key only for an explicit new intention', async () => {
  const declined = { ...payment, result: 'DECLINED' as const }
  vi.mocked(api).mockImplementation(async (_path, method) => method === 'POST' ? declined : summary())
  view(); await clickPay(); await screen.findByText('Intento rechazado: SIM-P-FIXTURE')
  const next = await screen.findByRole('button', { name: 'Nuevo intento simulado' })
  await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  await waitFor(() => expect(vi.mocked(api).mock.calls.filter(c => c[1] === 'POST')).toHaveLength(2))
  const calls = vi.mocked(api).mock.calls.filter(c => c[1] === 'POST')
  expect(calls[0][3]).not.toBe(calls[1][3])
})
it('preserves the same key and body for a retry after network failure', async () => {
  let calls = 0
  vi.mocked(api).mockImplementation(async (_path, method) => {
    if (method !== 'POST') return summary()
    if (++calls === 1) throw new Error('Synthetic network failure')
    return payment
  })
  view(); await clickPay(); fireEvent.click(await screen.findByRole('button', { name: 'Reintentar mismo pago' }))
  await screen.findByText('Intento aprobado: SIM-P-FIXTURE')
  const writes = vi.mocked(api).mock.calls.filter(c => c[1] === 'POST'); expect(writes[0]).toEqual(writes[1])
})
it('handles 409 without attempting another charge automatically', async () => {
  vi.mocked(api).mockImplementation(async (_path, method) => {
    if (method === 'POST') throw new ApiError(409, 'RESERVATION_ALREADY_PAID', 'La reserva ya tiene un pago aprobado.')
    return summary()
  })
  view(); await clickPay(); expect(await screen.findByRole('alert')).toHaveTextContent('La reserva ya tiene un pago aprobado.')
  fireEvent.click(screen.getByRole('button', { name: 'Actualizar pagos' }))
  await waitFor(() => expect(vi.mocked(api).mock.calls.filter(c => c[0].includes('?'))).toHaveLength(2))
  expect(vi.mocked(api).mock.calls.filter(c => c[1] === 'POST')).toHaveLength(1)
})
it('renders historical attempts, references and a complete refund', async () => {
  vi.mocked(api).mockResolvedValue(summary([refunded], 'REFUNDED')); view()
  await screen.findByText('Estado de pago: Reembolsada')
  expect(screen.getByText('Referencia: SIM-P-FIXTURE')).toBeInTheDocument()
  expect(screen.getByText(/Reembolso completo:.*251.00/)).toBeInTheDocument()
  expect(screen.getByText('Motivo: Cambio de planes')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /reembolsar|pagar/i })).not.toBeInTheDocument()
})
it('shows loading and allows a failed history request to be retried', async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'Error sintético.')).mockResolvedValue(summary())
  view(); expect(screen.getByText('Cargando pagos…')).toBeInTheDocument()
  await screen.findByText('Error sintético.'); fireEvent.click(screen.getByRole('button', { name: 'Reintentar consulta de pagos' }))
  await screen.findByText('No hay intentos de pago.')
})
it('does not expose payment data or actions for a foreign reservation', async () => {
  vi.mocked(api).mockRejectedValue(new ApiError(404, 'RESERVATION_NOT_FOUND', 'Reserva no encontrada.')); view()
  expect(await screen.findByRole('alert')).toHaveTextContent('Reserva no encontrada.')
  expect(screen.queryByText('Referencia: SIM-P-FIXTURE')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /pagar/i })).not.toBeInTheDocument()
})
it('does not show private payments to an anonymous visitor', () => {
  auth.user = null; view(); expect(api).not.toHaveBeenCalled(); expect(screen.queryByText('Pagos simulados')).not.toBeInTheDocument()
})
it.each(['CLIENTE', 'EMPLEADO', 'ADMIN'] as const)('allows %s to initiate the server-authorized simulation', async role => {
  auth.user = { ...user, role }; vi.mocked(api).mockImplementation(async (_path, method) => method === 'POST' ? payment : summary())
  view(); await clickPay(); await screen.findByText('Intento aprobado: SIM-P-FIXTURE')
})
it('paginates payment history on the server', async () => {
  vi.mocked(api).mockImplementation(async path => summary([payment], 'PAID', Number(new URL('http://test' + path).searchParams.get('page')), 3))
  view(); await screen.findByText('Referencia: SIM-P-FIXTURE'); fireEvent.click(screen.getByRole('button', { name: 'Más pagos' }))
  await screen.findByText('Página 2 de 3'); expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('page=1')
})
it('does not permit payment when the reservation is no longer payable', async () => {
  vi.mocked(api).mockResolvedValue({ ...summary(), canPay: false, amountDue: 0 }); view()
  await screen.findByText('No hay intentos de pago.'); expect(screen.queryByRole('button', { name: /pagar/i })).not.toBeInTheDocument()
})
it('paid cancellation refreshes the actual detail panel and displays the automatic refund', async () => {
  let cancelled = false
  const reservation = { id: 'reservation', code: 'R-FIXTURE', roomCode: '101', roomTypeName: 'Suite', checkIn: '2030-10-10', checkOut: '2030-10-12', nights: 2, guests: 2, agreedNightlyRate: 125.5, totalAmount: 251, currency: 'PEN', version: 0, status: 'CONFIRMED', canCancel: true }
  vi.mocked(api).mockImplementation(async (path, method) => {
    if (method === 'POST') cancelled = true
    if (path.includes('/payments')) return summary([cancelled ? refunded : payment], cancelled ? 'REFUNDED' : 'PAID')
    return cancelled ? { ...reservation, status: 'CANCELLED', canCancel: false, version: 1, cancellationReason: 'Cambio de planes' } : reservation
  })
  view(<Routes><Route path="/reservations/:id" element={<ReservationDetail />} /></Routes>, '/reservations/reservation')
  await screen.findByText('Estado de pago: Pagada')
  fireEvent.change(screen.getByLabelText('Motivo de cancelación'), { target: { value: 'Cambio de planes' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar cancelación' }))
  await screen.findByText('Estado: Cancelada'); await screen.findByText('Estado de pago: Reembolsada')
  expect(screen.getByText(/Reembolso completo:/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /reembolsar/i })).not.toBeInTheDocument()
})
