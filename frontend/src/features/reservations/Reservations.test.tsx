import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { ApiError, api, type User } from '../auth/api/auth'
import { BookingAction } from './BookingAction'
import { ReservationDetail, ReservationHistory } from './ReservationPages'
import type { Reservation } from './api'

const auth = vi.hoisted(() => ({ user: null as User | null, ready: true }))
vi.mock('../auth/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../auth/api/auth', async importOriginal => ({ ...await importOriginal<typeof import('../auth/api/auth')>(), api: vi.fn() }))
const user: User = { id: '00000000-0000-4000-8000-000000000001', name: 'Ana', email: 'ana@example.test', role: 'CLIENTE', createdAt: '' }
const room = { id: '00000000-0000-4000-8000-000000000002', code: '101', floor: 1,
  roomType: { id: '00000000-0000-4000-8000-000000000003', name: 'Doble', description: 'Test', capacity: 2, basePrice: 125.5, currency: 'PEN' }, nights: 2, estimatedTotal: 251 }
const filter = { checkIn: '2027-10-10', checkOut: '2027-10-12', guests: '2', roomTypeId: '', minPrice: '', maxPrice: '', size: '6' as const, sort: 'code,asc' as const }
const reservation: Reservation = { id: '00000000-0000-4000-8000-000000000004', code: 'R-FIXTURE', customerId: user.id, createdBy: user.id,
  roomId: room.id, roomCode: '101', roomTypeName: 'Doble', checkIn: filter.checkIn, checkOut: filter.checkOut, guests: 2, nights: 2,
  status: 'CONFIRMED', agreedNightlyRate: 125.5, totalAmount: 251, currency: 'PEN', cancellationReason: null, cancelledBy: null, cancelledAt: null, version: 0, canCancel: true }
const page = (items: unknown[], index = 0, totalPages = 1) => ({ items, page: index, size: 6, totalElements: items.length, totalPages })
function view(element: ReactNode, route = '/') {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  render(<QueryClientProvider client={cache}><MemoryRouter initialEntries={[route]}>{element}</MemoryRouter></QueryClientProvider>)
  return cache
}
const booking = (refresh = vi.fn()) => <BookingAction room={room} filter={filter} refresh={refresh} />
const open = () => fireEvent.click(screen.getByRole('button', { name: 'Reservar' }))
const confirm = () => fireEvent.click(screen.getByRole('button', { name: 'Confirmar reserva' }))
beforeEach(() => { vi.resetAllMocks(); auth.user = user; vi.mocked(api).mockResolvedValue(reservation) })

it('invites anonymous visitors to log in without allowing creation', () => {
  auth.user = null; view(booking())
  expect(screen.getByRole('link', { name: 'Inicia sesión para reservar' })).toHaveAttribute('href', '/login')
  expect(api).not.toHaveBeenCalled()
})
it('reviews dates and estimate, creates only approved fields and displays confirmation', async () => {
  view(booking()); open()
  expect(screen.getByText(/2027-10-10 al 2027-10-12/)).toBeInTheDocument()
  expect(screen.getByText(/Total estimado:.*251.00/)).toBeInTheDocument()
  confirm(); await screen.findByText('Reserva confirmada: R-FIXTURE')
  expect(api).toHaveBeenCalledWith('/reservations', 'POST', { roomId: room.id, checkIn: filter.checkIn, checkOut: filter.checkOut, guests: 2 }, expect.any(String))
  expect(screen.getByRole('link', { name: 'Ver reserva' })).toHaveAttribute('href', '/reservations/' + reservation.id)
})
it('disables double submission while a request is in progress', async () => {
  vi.mocked(api).mockReturnValue(new Promise(() => {})); view(booking()); open(); confirm()
  expect(await screen.findByRole('button', { name: 'Confirmando…' })).toBeDisabled()
  expect(api).toHaveBeenCalledTimes(1)
})
it('reuses the same key and payload after an uncertain network failure', async () => {
  vi.mocked(api).mockRejectedValueOnce(new Error('Network')).mockResolvedValue(reservation)
  view(booking()); open(); confirm()
  fireEvent.click(await screen.findByRole('button', { name: 'Reintentar reserva' }))
  await screen.findByText('Reserva confirmada: R-FIXTURE')
  expect(vi.mocked(api).mock.calls[0]).toEqual(vi.mocked(api).mock.calls[1])
})
it('generates different keys for separate booking intentions', async () => {
  view(<>{booking()}<BookingAction room={{ ...room, id: 'other-room' }} filter={filter} refresh={vi.fn()} /></>)
  fireEvent.click(screen.getAllByRole('button', { name: 'Reservar' })[0]); confirm()
  await screen.findByText('Reserva confirmada: R-FIXTURE')
  open(); confirm()
  await waitFor(() => expect(api).toHaveBeenCalledTimes(2))
  expect(vi.mocked(api).mock.calls[0][3]).not.toBe(vi.mocked(api).mock.calls[1][3])
})
it('handles ROOM_NOT_AVAILABLE by offering a fresh availability search', async () => {
  const refresh = vi.fn(); vi.mocked(api).mockRejectedValue(new ApiError(409, 'ROOM_NOT_AVAILABLE', 'Conflict'))
  view(booking(refresh)); open(); confirm()
  expect(await screen.findByRole('alert')).toHaveTextContent('La habitación ya no está disponible')
  fireEvent.click(screen.getByRole('button', { name: 'Actualizar disponibilidad' })); expect(refresh).toHaveBeenCalledOnce()
})
it('shows other API errors without claiming success', async () => {
  vi.mocked(api).mockRejectedValue(new ApiError(400, 'INVALID_AMOUNT', 'Importe inválido.'))
  view(booking()); open(); confirm(); expect(await screen.findByRole('alert')).toHaveTextContent('Importe inválido.')
  expect(screen.queryByText(/Reserva confirmada/)).not.toBeInTheDocument()
})
it('lets staff choose an existing customer and uses the staff endpoint', async () => {
  auth.user = { ...user, id: 'staff', role: 'EMPLEADO' }
  vi.mocked(api).mockImplementation(async path => path.startsWith('/staff/customers?') ? page([user]) : reservation)
  view(booking()); fireEvent.click(screen.getByRole('button', { name: 'Reservar para cliente' }))
  expect(screen.getByRole('button', { name: 'Confirmar reserva' })).toBeDisabled()
  await screen.findByRole('option', { name: 'Ana · ana@example.test' })
  fireEvent.change(screen.getByLabelText('Cliente'), { target: { value: user.id } }); confirm()
  await screen.findByText('Reserva confirmada: R-FIXTURE')
  expect(api).toHaveBeenLastCalledWith('/staff/reservations', 'POST', expect.objectContaining({ customerId: user.id }), expect.any(String))
})
it('renders own history, prices, status and detail links', async () => {
  vi.mocked(api).mockResolvedValue(page([reservation])); view(<ReservationHistory />)
  expect(screen.getByRole('heading', { name: 'Mis reservas' })).toBeInTheDocument()
  await screen.findByText('R-FIXTURE'); expect(screen.getByText('Estado: Confirmada')).toBeInTheDocument()
  expect(screen.getByText(/125.50 por noche/)).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Ver detalle de reserva' })).toHaveAttribute('href', '/reservations/' + reservation.id)
  expect(screen.queryByLabelText('Cliente')).not.toBeInTheDocument()
})
it('handles empty history and disabled pages', async () => {
  vi.mocked(api).mockResolvedValue(page([], 0, 0)); view(<ReservationHistory />)
  await screen.findByText('No hay reservas que coincidan.')
  expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
})
it('handles history loading and errors', async () => {
  vi.mocked(api).mockRejectedValue(new ApiError(401, 'INVALID_SESSION', 'Sesión vencida.')); view(<ReservationHistory />)
  expect(screen.getByText('Cargando reservas…')).toBeInTheDocument()
  expect(await screen.findByRole('alert')).toHaveTextContent('Sesión vencida.')
})
it('paginates and resets the page when filtering history', async () => {
  vi.mocked(api).mockImplementation(async path => page([reservation], Number(new URL('http://test' + path).searchParams.get('page')), 3))
  view(<ReservationHistory />); await screen.findByText('R-FIXTURE')
  fireEvent.click(screen.getByRole('button', { name: 'Siguiente' })); await screen.findByText('Página 2 de 3')
  fireEvent.change(screen.getByLabelText('Estado'), { target: { value: 'CANCELLED' } })
  fireEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))
  await waitFor(() => {
    const params = new URL('http://test' + vi.mocked(api).mock.calls.at(-1)![0]).searchParams
    expect(params.get('page')).toBe('0'); expect(params.get('status')).toBe('CANCELLED')
  })
})
function detail() { return <Routes><Route path="/reservations/:id" element={<ReservationDetail />} /></Routes> }
it('validates cancellation reason, sends version and updates detail and caches', async () => {
  let cancelled = false
  vi.mocked(api).mockImplementation(async (_path, method) => {
    if (method === 'POST') cancelled = true
    return cancelled ? { ...reservation, status: 'CANCELLED', canCancel: false, cancellationReason: 'Cambio de planes', version: 1 } : reservation
  })
  const cache = view(detail(), '/reservations/' + reservation.id)
  const invalidation = vi.spyOn(cache, 'invalidateQueries')
  fireEvent.click(await screen.findByRole('button', { name: 'Confirmar cancelación' }))
  await screen.findByText('Indica el motivo.')
  fireEvent.change(screen.getByLabelText('Motivo de cancelación'), { target: { value: 'Cambio de planes' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar cancelación' }))
  await screen.findByText('Estado: Cancelada')
  expect(api).toHaveBeenCalledWith('/reservations/' + reservation.id + '/cancel', 'POST', { version: 0, reason: 'Cambio de planes' })
  expect(invalidation).toHaveBeenCalledWith({ queryKey: ['availability'] })
})
it('hides cancellation when the backend denies it', async () => {
  vi.mocked(api).mockResolvedValue({ ...reservation, canCancel: false }); view(detail(), '/reservations/' + reservation.id)
  await screen.findByText('R-FIXTURE'); expect(screen.queryByRole('button', { name: 'Confirmar cancelación' })).not.toBeInTheDocument()
})
it('shows inaccessible reservation errors without leaking details', async () => {
  vi.mocked(api).mockRejectedValue(new ApiError(404, 'RESERVATION_NOT_FOUND', 'Reserva no encontrada.'))
  view(detail(), '/reservations/foreign'); expect(await screen.findByRole('alert')).toHaveTextContent('Reserva no encontrada.')
  expect(screen.queryByText('R-FIXTURE')).not.toBeInTheDocument()
})
it('shows staff history and the customer directory without reception operations', async () => {
  auth.user = { ...user, role: 'ADMIN' }; vi.mocked(api).mockResolvedValue(page([])); view(<ReservationHistory />)
  await screen.findByText('No hay reservas que coincidan.')
  expect(screen.getByRole('heading', { name: 'Reservas del hotel' })).toBeInTheDocument()
  expect(screen.getByLabelText('Cliente')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /check-in|pagar/i })).not.toBeInTheDocument()
})
