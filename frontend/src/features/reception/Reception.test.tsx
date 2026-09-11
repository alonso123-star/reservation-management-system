import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { ApiError, api, type User } from '../auth/api/auth'
import { ReceptionPanel } from './ReceptionPanel'
import { ReceptionPage } from './ReceptionPage'
import { ReservationDetail } from '../reservations/ReservationPages'
import type { Reservation } from '../reservations/api'

const auth = vi.hoisted(() => ({ user: null as User | null, ready: true }))
vi.mock('../auth/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../auth/api/auth', async original => ({ ...await original<typeof import('../auth/api/auth')>(), api: vi.fn() }))
vi.mock('../payments/PaymentPanel', () => ({ PaymentPanel: () => null }))
const employee: User = { id: 'staff', name: 'Personal', email: 'staff@example.test', role: 'EMPLEADO', createdAt: '' }
const reservation: Reservation = {
  id: 'reservation', code: 'R-RECEPTION', customerId: 'customer', createdBy: 'customer', roomId: 'room', roomCode: '701', roomTypeName: 'Suite',
  checkIn: '2030-10-10', checkOut: '2030-10-15', guests: 2, nights: 5, status: 'CONFIRMED', agreedNightlyRate: 125.5, totalAmount: 627.5, currency: 'PEN',
  cancellationReason: null, cancelledBy: null, cancelledAt: null, version: 0, canCancel: false, checkedInAt: null, checkedOutAt: null,
  reception: { customerName: 'Huésped de prueba', hotelTimeZone: 'America/Lima', hotelDate: '2030-10-10', evaluatedAt: '2030-10-10T17:00:00Z',
    arrivalDeadline: '2030-10-11T03:00:00Z', fullyPaid: true, canCheckIn: true, canCheckOut: false, canNoShow: false },
}
const page = (r: Reservation[] = [reservation]) => ({ items: r, page: 0, size: 6, totalPages: 1, totalElements: r.length })
function view(element: ReactNode = <ReceptionPanel reservation={reservation} />, path = '/') {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  render(<QueryClientProvider client={cache}><MemoryRouter initialEntries={[path]}>{element}</MemoryRouter></QueryClientProvider>)
  return cache
}
beforeEach(() => { vi.resetAllMocks(); auth.user = employee; auth.ready = true; vi.mocked(api).mockResolvedValue(reservation) })

it.each(['EMPLEADO', 'ADMIN'] as const)('allows %s to review paid arrival before confirming', role => {
  auth.user = { ...employee, role }; view()
  expect(screen.getByText('Pago para ingreso: Completo y no reembolsado')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Check-in' }))
  expect(screen.getByRole('group')).toHaveTextContent('R-RECEPTION · Huésped de prueba · Habitación 701 · Suite')
  expect(screen.getByRole('group')).toHaveTextContent('2030-10-10 al 2030-10-15')
  expect(api).not.toHaveBeenCalled()
})
it('does not expose controls or staff lists to CLIENTE', () => {
  auth.user = { ...employee, role: 'CLIENTE' }; view(<><ReceptionPanel reservation={reservation} /><ReceptionPage /></>)
  expect(screen.queryByRole('button', { name: 'Check-in' })).not.toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('No tienes permiso para operar recepción.')
  expect(api).not.toHaveBeenCalled()
})
it('waits for authentication and does not query private lists', () => {
  auth.ready = false; auth.user = null; view(<ReceptionPage />)
  expect(screen.getByRole('status')).toHaveTextContent('Recuperando tu sesión')
  expect(api).not.toHaveBeenCalled()
})
it('explains unpaid check-in without offering an invalid action', () => {
  view(<ReceptionPanel reservation={{ ...reservation, reception: { ...reservation.reception!, fullyPaid: false, canCheckIn: false } }} />)
  expect(screen.getByText('Completa el pago antes de registrar el check-in.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Check-in' })).not.toBeInTheDocument()
})
it('uses server capabilities and hotel time even when browser date differs', () => {
  view(<ReceptionPanel reservation={{ ...reservation, reception: { ...reservation.reception!, canCheckIn: false, canNoShow: true } }} />)
  expect(screen.getByText('Fecha del hotel: 2030-10-10 · America/Lima')).toBeInTheDocument()
  expect(screen.getByText(/Plazo de llegada:/)).toHaveTextContent('America/Lima')
  expect(screen.queryByRole('button', { name: 'Check-in' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'No-show' })).toBeInTheDocument()
})
it('submits only version and disables double clicks while pending', async () => {
  vi.mocked(api).mockImplementation(() => new Promise(() => {})); view()
  fireEvent.click(screen.getByRole('button', { name: 'Check-in' })); fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación' }))
  expect(await screen.findByRole('button', { name: 'Registrando…' })).toBeDisabled()
  expect(api).toHaveBeenCalledExactlyOnceWith('/reservations/reservation/check-in', 'POST', { version: 0 })
})
it.each([
  ['check-in', 'Check-in', 'CONFIRMED', 'CHECKED_IN', 'Ingresada'],
  ['check-out', 'Check-out', 'CHECKED_IN', 'CHECKED_OUT', 'Finalizada'],
  ['no-show', 'No-show', 'CONFIRMED', 'NO_SHOW', 'No presentado'],
] as const)('%s refreshes the real reservation detail after confirmation', async (action, label, from, to, statusLabel) => {
  let current: Reservation = { ...reservation, status: from, reception: { ...reservation.reception!, canCheckIn: action === 'check-in', canCheckOut: action === 'check-out', canNoShow: action === 'no-show' } }
  vi.mocked(api).mockImplementation(async (_path, method) => {
    if (method === 'POST') current = { ...current, status: to, version: 1, checkedInAt: to === 'CHECKED_IN' ? '2030-10-10T17:00:00Z' : null,
      checkedOutAt: to === 'CHECKED_OUT' ? '2030-10-10T17:01:00Z' : null,
      reception: { ...current.reception!, canCheckIn: false, canCheckOut: to === 'CHECKED_IN', canNoShow: false } }
    return current
  })
  const cache = view(<Routes><Route path="/reservations/:id" element={<ReservationDetail />} /></Routes>, '/reservations/reservation')
  const invalidate = vi.spyOn(cache, 'invalidateQueries')
  fireEvent.click(await screen.findByRole('button', { name: label }))
  if (action === 'check-out') expect(screen.getByRole('group')).toHaveTextContent('No libera noches ni genera reembolso')
  if (action === 'no-show') expect(screen.getByRole('group')).toHaveTextContent('Los pagos se conservan sin reembolso automático')
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación' }))
  await screen.findByText('Estado: ' + statusLabel)
  expect(api).toHaveBeenCalledWith('/reservations/reservation/' + action, 'POST', { version: 0 })
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['availability'] })
  expect(screen.queryByRole('group')).not.toBeInTheDocument()
})
it.each(['CANCELLED', 'NO_SHOW', 'CHECKED_OUT'] as const)('hides all transition controls for %s', status => {
  view(<ReceptionPanel reservation={{ ...reservation, status, reception: { ...reservation.reception!, canCheckIn: false, canCheckOut: false, canNoShow: false } }} />)
  expect(screen.queryByRole('button', { name: /^(Check-in|Check-out|No-show)$/ })).not.toBeInTheDocument()
})
it('handles 409 without retrying a transition automatically', async () => {
  vi.mocked(api).mockRejectedValue(new ApiError(409, 'STALE_VERSION', 'La reserva cambió.')); view()
  fireEvent.click(screen.getByRole('button', { name: 'Check-in' })); fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('La reserva cambió.')
  expect(screen.queryByRole('button', { name: 'Confirmar operación' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: 'Actualizar recepción' })).toBeInTheDocument()
})
it('handles uncertain network responses by requiring a state refresh', async () => {
  vi.mocked(api).mockRejectedValue(new Error('Synthetic network failure')); view()
  fireEvent.click(screen.getByRole('button', { name: 'Check-in' })); fireEvent.click(screen.getByRole('button', { name: 'Confirmar operación' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('una respuesta perdida puede haber completado la operación')
  expect(screen.queryByRole('button', { name: 'Confirmar operación' })).not.toBeInTheDocument()
})
it('loads arrivals and switches to checked-in guests using existing paginated history', async () => {
  vi.mocked(api).mockResolvedValue(page()); view(<ReceptionPage />)
  expect(screen.getByRole('status')).toHaveTextContent('Cargando recepción')
  await screen.findByRole('heading', { name: 'R-RECEPTION' })
  expect(vi.mocked(api).mock.calls[0][0]).toContain('status=CONFIRMED')
  fireEvent.click(screen.getByRole('button', { name: 'Huéspedes alojados' }))
  await waitFor(() => expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('status=CHECKED_IN'))
  expect(await screen.findByRole('link', { name: 'Consultar huésped, pago y acciones' })).toHaveAttribute('href', '/reservations/reservation')
})
it('supports code/date search and empty states', async () => {
  vi.mocked(api).mockResolvedValue(page([])); view(<ReceptionPage />)
  await screen.findByText('No hay reservas en esta vista.')
  fireEvent.change(screen.getByLabelText('Código de reserva'), { target: { value: 'R-LOOKUP' } })
  fireEvent.change(screen.getByLabelText('Llegada hasta'), { target: { value: '2030-10-10' } })
  fireEvent.click(screen.getByRole('button', { name: 'Buscar reservas' }))
  await waitFor(() => expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('code=R-LOOKUP'))
  expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('checkInTo=2030-10-10')
})
it('shows list errors and retries the read only', async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'Error de prueba')).mockResolvedValue(page([]))
  view(<ReceptionPage />); expect(await screen.findByRole('alert')).toHaveTextContent('Error de prueba')
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar' })); await screen.findByText('No hay reservas en esta vista.')
})
