import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { CatalogPage, CatalogDetail } from './CatalogPage'
import { TypeForm, RoomForm } from './CatalogForms'
import { RoleGuard } from './RoleGuard'
import { api, publicGet, ApiError, type User } from '../auth/api/auth'
import type { TypeItem, RoomItem } from './api'

const auth = vi.hoisted(() => ({ user: null as User | null, ready: true }))
vi.mock('../auth/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../auth/api/auth', async importOriginal => ({
  ...await importOriginal<typeof import('../auth/api/auth')>(), api: vi.fn(), publicGet: vi.fn(),
}))
const type: TypeItem = { id: 'a76d0b83-7f60-461e-b665-2d78a85dff5e', name: 'Doble', description: 'Dos camas', capacity: 2, basePrice: 125.50, currency: 'PEN', active: true, version: 3 }
const room: RoomItem = { id: 'c76d0b83-7f60-461e-b665-2d78a85dff5e', code: '101', floor: 1, roomType: type, active: true, version: 5, operationalStatus: 'ACTIVE' }
const page = (items: unknown[], totalPages = 1) => ({ items, page: 0, size: 6, totalElements: items.length, totalPages })
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
function role(value: User['role']) { auth.user = { id: value, name: value, role: value, email: 'test@example.test', createdAt: '' } }
function view(children: ReactNode) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
    <MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}
beforeEach(() => {
  vi.resetAllMocks(); auth.user = null; auth.ready = true
  vi.mocked(publicGet).mockResolvedValue(page([type]))
  vi.mocked(api).mockImplementation(async (path, method) => method ? {} : page(path.startsWith('/staff/rooms?') ? [room] : [type]))
})

describe('catalog browsing and roles', () => {
  it('renders public types and details without administrative actions', async () => {
    view(<CatalogPage kind="types" />)
    expect(await screen.findByRole('heading', { name: 'Doble' })).toBeInTheDocument()
    expect(screen.getByText(/125.50/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver detalle' })).toHaveAttribute('href', '/catalog/types/' + type.id)
    expect(screen.queryByRole('button', { name: 'Crear tipo' })).not.toBeInTheDocument()
    expect(api).not.toHaveBeenCalled()
  })
  it('sends filters and pagination to the server and resets the page on filtering', async () => {
    vi.mocked(publicGet).mockResolvedValue(page([type], 3))
    view(<CatalogPage kind="types" />)
    await screen.findByText('Doble')
    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() => expect(publicGet).toHaveBeenLastCalledWith('/room-types?page=1&size=6'))
    change('Buscar por nombre', 'Doble'); change('Capacidad mínima', '2'); change('Por página', '12')
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))
    await waitFor(() => {
      const url = new URL('http://test' + vi.mocked(publicGet).mock.calls.at(-1)![0])
      expect(url.searchParams.get('q')).toBe('Doble')
      expect(url.searchParams.get('minCapacity')).toBe('2')
      expect(url.searchParams.get('page')).toBe('0')
      expect(url.searchParams.get('size')).toBe('12')
    })
  })
  it('shows loading while a request is pending', () => {
    vi.mocked(publicGet).mockReturnValue(new Promise(() => {}))
    view(<CatalogPage kind="types" />)
    expect(screen.getByRole('status')).toHaveTextContent('Cargando catálogo')
  })
  it('shows an empty catalog and disables unavailable pages', async () => {
    vi.mocked(publicGet).mockResolvedValue(page([]))
    view(<CatalogPage kind="types" />)
    expect(await screen.findByText(/No hay elementos/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
  })
  it('allows retry after a network error', async () => {
    vi.mocked(publicGet).mockRejectedValueOnce(new Error('Network')).mockResolvedValue(page([type]))
    view(<CatalogPage kind="types" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo conectar')
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Doble')).toBeInTheDocument()
  })
  it.each([401, 403])('shows a %i inventory error', async status => {
    role('ADMIN'); vi.mocked(api).mockRejectedValue(new ApiError(status, 'DENIED', 'Acceso denegado ' + status))
    view(<CatalogPage kind="types" staff />)
    expect(await screen.findByRole('alert')).toHaveTextContent(String(status))
  })
  it('shows a 404 for a hidden or missing public detail', async () => {
    vi.mocked(publicGet).mockRejectedValue(new ApiError(404, 'NOT_FOUND', 'Tipo no encontrado.'))
    view(<CatalogDetail kind="types" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Tipo no encontrado.')
    expect(screen.getByRole('link', { name: 'Volver al catálogo' })).toBeInTheDocument()
  })
  it('lets ADMIN deactivate a type with its version', async () => {
    role('ADMIN'); view(<CatalogPage kind="types" staff />)
    fireEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/room-types/' + type.id, 'PATCH', { version: 3, active: false }))
    expect(await screen.findByText('Cambios guardados correctamente.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Crear tipo' })).toBeInTheDocument()
  })
  it('lets EMPLEADO change only room status and submits the version', async () => {
    role('EMPLEADO'); view(<CatalogPage kind="rooms" staff />)
    await screen.findByRole('heading', { name: 'Habitación 101' })
    expect(screen.queryByRole('button', { name: /Crear habitación|Editar 101|Desactivar/ })).not.toBeInTheDocument()
    change('Estado de 101', 'MAINTENANCE')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar estado' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/rooms/' + room.id + '/status', 'PATCH', { version: 5, operationalStatus: 'MAINTENANCE' }))
  })
  it('recovers the list after a status conflict without overwriting', async () => {
    role('EMPLEADO'); vi.mocked(api).mockImplementation(async (path, method) => {
      if (method) throw new ApiError(409, 'STALE_VERSION', 'El registro cambió. Actualiza los datos.')
      return page(path.startsWith('/staff/rooms?') ? [room] : [type])
    })
    view(<CatalogPage kind="rooms" staff />)
    await screen.findByRole('heading', { name: 'Habitación 101' })
    change('Estado de 101', 'OUT_OF_SERVICE')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar estado' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El registro cambió')
    fireEvent.click(screen.getByRole('button', { name: 'Actualizar listado' }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })
  it('prevents CLIENTE from opening inventory', () => {
    role('CLIENTE'); view(<RoleGuard><p>Inventario privado</p></RoleGuard>)
    expect(screen.getByRole('alert')).toHaveTextContent('No tienes permiso')
    expect(screen.queryByText('Inventario privado')).not.toBeInTheDocument()
  })
  it('redirects anonymous inventory access to login', async () => {
    view(<Routes><Route path="/" element={<RoleGuard>Privado</RoleGuard>} /><Route path="/login" element={<p>Login</p>} /></Routes>)
    expect(await screen.findByText('Login')).toBeInTheDocument()
  })
})

describe('catalog administration forms', () => {
  const saved = vi.fn(), cancel = vi.fn(), reload = vi.fn().mockResolvedValue(undefined)
  function typeForm(initial?: TypeItem) { view(<TypeForm initial={initial} onSaved={saved} onCancel={cancel} onReload={reload} />) }
  function roomForm(initial?: RoomItem) { view(<RoomForm initial={initial} onSaved={saved} onCancel={cancel} onReload={reload} />) }
  it('validates required type name and positive money/capacity before sending', async () => {
    typeForm(); change('Capacidad', '0'); change('Tarifa base por noche', '-1')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar tipo' }))
    expect(await screen.findByText('Introduce el nombre.')).toBeInTheDocument()
    expect(screen.getAllByRole('alert').length).toBeGreaterThanOrEqual(3)
    expect(api).not.toHaveBeenCalled()
  })
  it('creates a type preserving the decimal text supplied by the user', async () => {
    typeForm(); change('Nombre del tipo', 'Suite'); change('Descripción', 'Vista al patio')
    change('Capacidad', '3'); change('Tarifa base por noche', '125.50')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar tipo' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/room-types', 'POST', { name: 'Suite', description: 'Vista al patio', capacity: 3, basePrice: '125.50', active: true }))
    expect(saved).toHaveBeenCalled()
  })
  it('edits an existing type with the version originally read', async () => {
    typeForm(type); change('Nombre del tipo', 'Doble superior')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar tipo' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/room-types/' + type.id, 'PATCH', expect.objectContaining({ name: 'Doble superior', version: 3 })))
  })
  it('offers a reload when the edited type is stale', async () => {
    vi.mocked(api).mockRejectedValue(new ApiError(409, 'STALE_VERSION', 'Otro usuario modificó el tipo.'))
    typeForm(type); fireEvent.click(screen.getByRole('button', { name: 'Guardar tipo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Otro usuario')
    fireEvent.click(screen.getByRole('button', { name: 'Recargar registro' }))
    expect(reload).toHaveBeenCalled(); expect(saved).not.toHaveBeenCalled()
  })
  it('validates room code, type and floor before sending', async () => {
    roomForm(); change('Piso', '201')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar habitación' }))
    expect(await screen.findByText('Introduce el código.')).toBeInTheDocument()
    expect(screen.getByText('Selecciona un tipo.')).toBeInTheDocument()
    expect(screen.getByText('Debe estar entre -5 y 200.')).toBeInTheDocument()
    expect(vi.mocked(api).mock.calls.every(([, method]) => !method)).toBe(true)
  })
  it('creates a room with the selected type and operational status', async () => {
    roomForm(); await screen.findByRole('option', { name: 'Doble' })
    change('Código de habitación', '201'); change('Tipo de habitación', type.id); change('Piso', '2')
    change('Estado operativo', 'MAINTENANCE')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar habitación' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/rooms', 'POST', { code: '201', roomTypeId: type.id, floor: 2, operationalStatus: 'MAINTENANCE', active: true }))
  })
  it('edits a room and preserves the version for concurrency control', async () => {
    roomForm(room); change('Piso', '3')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar habitación' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/rooms/' + room.id, 'PATCH', expect.objectContaining({ floor: 3, roomTypeId: type.id, version: 5 })))
  })
})
