import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AvailabilityPage } from './AvailabilityPage'
import { availabilitySchema } from './availability-schema'
import { publicGet, api, ApiError } from '../auth/api/auth'

vi.mock('../auth/api/auth', async importOriginal => ({
  ...await importOriginal<typeof import('../auth/api/auth')>(), publicGet: vi.fn(), api: vi.fn(),
}))
const type = { id: 'a76d0b83-7f60-461e-b665-2d78a85dff5e', name: 'Doble', description: 'Dos camas', capacity: 2, basePrice: 125.50, currency: 'PEN' }
const room = { id: 'c76d0b83-7f60-461e-b665-2d78a85dff5e', code: '101', floor: 1, roomType: type, nights: 2, estimatedTotal: 251 }
const page = (items: unknown[], index = 0, totalPages = 1) => ({ items, page: index, size: 6, totalElements: items.length, totalPages })
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const searches = () => vi.mocked(publicGet).mock.calls.filter(([path]) => path.startsWith('/rooms/availability?'))
function view() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
    <MemoryRouter><AvailabilityPage /></MemoryRouter></QueryClientProvider>)
}
function submit() { fireEvent.click(screen.getByRole('button', { name: 'Buscar habitaciones' })) }
function dates() { change('Entrada', '2026-10-10'); change('Salida', '2026-10-12') }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(publicGet).mockImplementation(async path => path.startsWith('/room-types?') ? page([type]) : page([room]))
})

describe('public availability search', () => {
  it('waits for a valid search and never invokes the authenticated client', async () => {
    view()
    await screen.findByRole('option', { name: 'Doble' })
    expect(screen.getByText(/Introduce fechas/)).toBeInTheDocument()
    expect(searches()).toHaveLength(0)
    expect(api).not.toHaveBeenCalled()
  })
  it('validates required dates and guests before sending a request', async () => {
    view(); change('Huéspedes', ''); submit()
    await screen.findByText('Introduce una fecha de entrada válida.')
    expect(screen.getByText('Introduce una fecha de salida válida.')).toBeInTheDocument()
    expect(screen.getByText('Indica un número entero positivo de huéspedes.')).toBeInTheDocument()
    expect(searches()).toHaveLength(0)
  })
  it.each(['2026-10-10', '2026-10-09'])('rejects equal/reversed departure %s', async departure => {
    view(); dates(); change('Salida', departure); submit()
    await screen.findByText('La salida debe ser posterior a la entrada.')
    expect(searches()).toHaveLength(0)
  })
  it.each(['0', '-1', '1.5'])('rejects invalid guests %s', async guests => {
    view(); dates(); change('Huéspedes', guests); submit()
    await screen.findByText('Indica un número entero positivo de huéspedes.')
    expect(searches()).toHaveLength(0)
  })
  it('renders server nights, nightly currency and estimate without a booking action', async () => {
    view(); dates(); submit()
    await screen.findByRole('heading', { name: 'Habitación 101' })
    expect(screen.getByText('2 noches')).toBeInTheDocument()
    expect(screen.getByText(/125.50/)).toBeInTheDocument()
    expect(screen.getByText(/Total estimado:.*251.00/)).toBeInTheDocument()
    expect(screen.getByText(/por noche · PEN/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver habitación' })).toHaveAttribute('href', '/catalog/rooms/' + room.id)
    expect(screen.queryByRole('button', { name: /reservar/i })).not.toBeInTheDocument()
    expect(api).not.toHaveBeenCalled()
    const query = new URL('http://test' + searches()[0][0]).searchParams
    expect(Object.fromEntries(query)).toEqual({ checkIn: '2026-10-10', checkOut: '2026-10-12', guests: '2', sort: 'code,asc', size: '6', page: '0' })
  })
  it('uses the backend estimate as received instead of recalculating prices', async () => {
    vi.mocked(publicGet).mockImplementation(async path => path.startsWith('/room-types?') ? page([type]) : page([{ ...room, estimatedTotal: 300.25 }]))
    view(); dates(); submit()
    await screen.findByText(/Total estimado:.*300.25/)
  })
  it('shows loading during the availability request', async () => {
    vi.mocked(publicGet).mockImplementation(path => path.startsWith('/room-types?') ? Promise.resolve(page([type])) : new Promise(() => {}))
    view(); dates(); submit()
    await screen.findByText('Buscando habitaciones…')
    expect(screen.getByRole('button', { name: 'Buscar habitaciones' })).toBeDisabled()
  })
  it('renders an empty result with disabled pagination', async () => {
    vi.mocked(publicGet).mockImplementation(async path => path.startsWith('/room-types?') ? page([type]) : page([], 0, 0))
    view(); dates(); submit()
    await screen.findByText('No hay habitaciones que coincidan con tu búsqueda.')
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
  })
  it('shows the server error and permits retry', async () => {
    let failed = false
    vi.mocked(publicGet).mockImplementation(async path => {
      if (path.startsWith('/room-types?')) return page([type])
      if (!failed) { failed = true; throw new ApiError(400, 'INVALID_STAY', 'Estancia inválida.') }
      return page([room])
    })
    view(); dates(); submit()
    await screen.findByText('Estancia inválida.')
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    await screen.findByRole('heading', { name: 'Habitación 101' })
  })
  it('sends optional filters and resets pagination when applying a new search', async () => {
    vi.mocked(publicGet).mockImplementation(async path => path.startsWith('/room-types?') ? page([type]) :
      page([room], Number(new URL('http://test' + path).searchParams.get('page')), 3))
    view(); dates(); submit()
    await screen.findByRole('heading', { name: 'Habitación 101' })
    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await screen.findByText('Página 2 de 3')
    change('Tipo de habitación', type.id); change('Precio mínimo por noche', '125.50')
    change('Precio máximo por noche', '200'); change('Ordenar por', 'basePrice,desc'); change('Por página', '12')
    submit()
    await waitFor(() => {
      const query = new URL('http://test' + searches().at(-1)![0]).searchParams
      expect(query.get('roomTypeId')).toBe(type.id)
      expect(query.get('minPrice')).toBe('125.50'); expect(query.get('maxPrice')).toBe('200')
      expect(query.get('sort')).toBe('basePrice,desc'); expect(query.get('size')).toBe('12')
      expect(query.get('page')).toBe('0')
    })
  })
  it('rejects reversed price bounds', async () => {
    view(); dates(); change('Precio mínimo por noche', '200'); change('Precio máximo por noche', '100'); submit()
    await screen.findByText('El precio máximo debe ser igual o mayor que el mínimo.')
    expect(searches()).toHaveLength(0)
  })
  it('refreshes unchanged searches and accepts past dates and one-night stays', async () => {
    view(); change('Entrada', '2020-02-28'); change('Salida', '2020-02-29'); submit()
    await screen.findByRole('heading', { name: 'Habitación 101' })
    submit()
    await waitFor(() => expect(searches()).toHaveLength(2))
  })
})

describe('availability form calendar and numeric boundaries', () => {
  const valid = { checkIn: '2024-02-29', checkOut: '2024-03-01', guests: '1', roomTypeId: '', minPrice: '0', maxPrice: '125.50', sort: 'code,asc', size: '6' }
  it('accepts a real leap day and inclusive equal price bounds', () => {
    expect(availabilitySchema.safeParse({ ...valid, minPrice: '125.50' }).success).toBe(true)
  })
  it.each([{ checkIn: '2026-02-30' }, { checkOut: 'invalid' }, { guests: '2147483648' }, { minPrice: '-1' },
    { minPrice: '0.001' }, { maxPrice: '10000000000' }, { roomTypeId: 'invalid' }])('rejects invalid input %j', fields => {
    expect(availabilitySchema.safeParse({ ...valid, ...fields }).success).toBe(false)
  })
})
