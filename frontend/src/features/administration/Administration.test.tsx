import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { api, ApiError, clearSession, type User } from '../auth/api/auth'
import { UsersPage, CreateUserPage, AdminUserDetailPage } from './UsersPage'
import { DashboardPage, AuditPage } from './ReportingPages'
import type { AdminUser, AuditEvent, Dashboard } from './api'

const auth = vi.hoisted(() => ({ user: null as User | null, ready: true }))
vi.mock('../auth/useAuth', () => ({ useAuth: () => auth }))
vi.mock('../auth/api/auth', async original => ({ ...await original<typeof import('../auth/api/auth')>(), api: vi.fn(), clearSession: vi.fn() }))
const admin: User = { id: 'admin', name: 'Admin sintético', email: 'admin@example.test', role: 'ADMIN', createdAt: '' }
const target: AdminUser = { id: 'target', name: 'Usuario sintético', email: 'target@example.test', role: 'CLIENTE', active: true, version: 0, createdAt: '2030-10-01T00:00:00Z', updatedAt: '2030-10-01T00:00:00Z' }
const page = <T,>(items: T[], totalPages = 1) => ({ items, page: 0, size: 10, totalElements: items.length, totalPages })
function view(element: ReactNode, path = '/') {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } })
  render(<QueryClientProvider client={cache}><MemoryRouter initialEntries={[path]}>{element}</MemoryRouter></QueryClientProvider>)
  return cache
}
function detail() { return view(<Routes><Route path="/admin/users/:id" element={<AdminUserDetailPage />} /></Routes>, '/admin/users/target') }
beforeEach(() => { vi.resetAllMocks(); auth.user = admin; auth.ready = true })

it.each(['CLIENTE', 'EMPLEADO'] as const)('denies %s all administrative screens without private requests', role => {
  auth.user = { ...admin, role }; view(<><UsersPage /><CreateUserPage /><AdminUserDetailPage /><DashboardPage /><AuditPage /></>)
  expect(screen.getAllByRole('alert')).toHaveLength(5); expect(api).not.toHaveBeenCalled()
})
it('waits for session recovery', () => { auth.ready = false; view(<UsersPage />); expect(screen.getByRole('status')).toHaveTextContent('Recuperando'); expect(api).not.toHaveBeenCalled() })
it('redirects an anonymous visitor to login', async () => {
  auth.user = null; view(<Routes><Route path="/" element={<UsersPage />} /><Route path="/login" element={<p>Acceso requerido</p>} /></Routes>)
  expect(await screen.findByText('Acceso requerido')).toBeInTheDocument(); expect(api).not.toHaveBeenCalled()
})
it('lists users, applies filters and paginates on the server', async () => {
  vi.mocked(api).mockResolvedValue(page([target], 2)); view(<UsersPage />)
  expect(screen.getByRole('status')).toHaveTextContent('Cargando usuarios')
  await screen.findByText(target.email)
  fireEvent.change(screen.getByLabelText('Nombre o correo'), { target: { value: 'target' } })
  fireEvent.change(screen.getByLabelText('Rol'), { target: { value: 'CLIENTE' } })
  fireEvent.change(screen.getByLabelText('Estado'), { target: { value: 'true' } })
  fireEvent.click(screen.getByRole('button', { name: 'Buscar usuarios' }))
  await waitFor(() => expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('q=target&role=CLIENTE&active=true'))
  fireEvent.click(await screen.findByRole('button', { name: 'Siguiente' }))
  await waitFor(() => expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('page=1'))
})
it('shows empty users and recovers from a read error', async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'Error sintético')).mockResolvedValue(page([])); view(<UsersPage />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Error sintético'); fireEvent.click(screen.getByText('Reintentar'))
  expect(await screen.findByText('No hay usuarios con estos filtros.')).toBeInTheDocument()
})
it('creates a user with only allowed fields and an explicitly selected role', async () => {
  vi.mocked(api).mockResolvedValue({ ...target, role: 'EMPLEADO' }); view(<CreateUserPage />)
  fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: target.name } })
  fireEvent.change(screen.getByLabelText('Correo'), { target: { value: target.email } })
  fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'Synthetic password 123!' } })
  fireEvent.change(screen.getByLabelText('Confirmar contraseña'), { target: { value: 'Synthetic password 123!' } })
  fireEvent.change(screen.getByLabelText('Rol inicial'), { target: { value: 'EMPLEADO' } })
  fireEvent.click(screen.getByRole('button', { name: 'Crear usuario' }))
  await screen.findByText('Usuario creado.')
  expect(api).toHaveBeenCalledExactlyOnceWith('/users', 'POST', { name: target.name, email: target.email, password: 'Synthetic password 123!', role: 'EMPLEADO' })
})
it('validates creation before sending credentials', async () => {
  view(<CreateUserPage />); fireEvent.click(screen.getByRole('button', { name: 'Crear usuario' }))
  await screen.findAllByRole('alert'); expect(api).not.toHaveBeenCalled()
})
it.each(['role', 'deactivate', 'activate'] as const)('confirms %s before mutating and updates the detail', async action => {
  let value = { ...target, active: action !== 'activate' }
  vi.mocked(api).mockImplementation(async (_path, method) => {
    if (method === 'PATCH') value = { ...value, version: 1, role: action === 'role' ? 'EMPLEADO' : value.role, active: action === 'role' ? value.active : !value.active }
    return value
  }); detail(); await screen.findByText(target.email + ' · CLIENTE · ' + (value.active ? 'Activo' : 'Inactivo'))
  if (action === 'role') fireEvent.change(screen.getByLabelText('Nuevo rol'), { target: { value: 'EMPLEADO' } })
  fireEvent.click(screen.getByText(action === 'role' ? 'Cambiar rol' : action === 'activate' ? 'Activar usuario' : 'Desactivar usuario'))
  expect(screen.getByRole('group')).toHaveTextContent('Se invalidarán las sesiones')
  expect(vi.mocked(api).mock.calls.filter(call => call[1] === 'PATCH')).toHaveLength(0)
  fireEvent.click(screen.getByText('Confirmar cambio'))
  await waitFor(() => expect(screen.queryByRole('group')).not.toBeInTheDocument())
  expect(api).toHaveBeenCalledWith('/users/target/' + (action === 'role' ? 'role' : 'status'), 'PATCH', action === 'role' ? { role: 'EMPLEADO', version: 0 } : { active: action === 'activate', version: 0 })
})
it.each(['LAST_ACTIVE_ADMIN', 'USER_VERSION_CONFLICT'])('handles %s without silently retrying or overwriting', async code => {
  vi.mocked(api).mockImplementation(async (_path, method) => { if (method === 'PATCH') throw new ApiError(409, code, code); return target }); detail()
  fireEvent.click(await screen.findByText('Desactivar usuario')); fireEvent.click(screen.getByText('Confirmar cambio'))
  expect(await screen.findByRole('alert')).toHaveTextContent(code); expect(screen.getByText('Confirmar cambio')).toBeDisabled()
  expect(vi.mocked(api).mock.calls.filter(c => c[1] === 'PATCH')).toHaveLength(1)
})
it('clears the local session and cache when the admin deactivates themselves', async () => {
  auth.user = { ...admin, id: 'target' }
  vi.mocked(api).mockImplementation(async (_path, method) => ({ ...target, role: 'ADMIN', active: method !== 'PATCH', version: method === 'PATCH' ? 1 : 0 }))
  const cache = detail(); fireEvent.click(await screen.findByText('Desactivar usuario'))
  expect(screen.getByRole('group')).toHaveTextContent('tu propia cuenta'); fireEvent.click(screen.getByText('Confirmar cambio'))
  await waitFor(() => expect(clearSession).toHaveBeenCalledOnce()); expect(cache.getQueryCache().getAll()).toHaveLength(0)
})
const metrics: Dashboard = { from: '2030-10-10', to: '2030-10-13', hotelTimeZone: 'America/Lima', currency: 'PEN', nights: 3, reservationsCreated: 1, arrivals: 1, departures: 1, eligibleRooms: 2, roomNightsOccupied: 3, roomNightsAvailable: 6, occupancyPercent: 50, approvedPayments: 300.30, refunds: 80.10, netRevenue: 220.20 }
it('requires an explicit dashboard period and displays formulas, money and timezone', async () => {
  vi.mocked(api).mockResolvedValue(metrics); view(<DashboardPage />); expect(api).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Desde'), { target: { value: metrics.from } }); fireEvent.change(screen.getByLabelText('Hasta (excluido)'), { target: { value: metrics.to } })
  fireEvent.click(screen.getByText('Consultar métricas')); await screen.findByText('50 %')
  expect(screen.getByText(/Periodo:/)).toHaveTextContent('America/Lima'); expect(screen.getByText(/3 \/ 6 noches/)).toBeInTheDocument()
  expect(screen.getByText(/220[.,]20/)).toBeInTheDocument(); expect(screen.getByText(/Cada movimiento/)).toHaveTextContent('DECLINED excluidos')
  expect(api).toHaveBeenCalledWith('/admin/dashboard?from=2030-10-10&to=2030-10-13')
})
it('rejects reversed dashboard dates locally', () => {
  view(<DashboardPage />); fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2030-10-13' } }); fireEvent.change(screen.getByLabelText('Hasta (excluido)'), { target: { value: '2030-10-10' } })
  fireEvent.click(screen.getByText('Consultar métricas')); expect(screen.getByRole('alert')).toHaveTextContent('anterior'); expect(api).not.toHaveBeenCalled()
})
it('represents zero denominator without a fabricated percentage', async () => {
  vi.mocked(api).mockResolvedValue({ ...metrics, occupancyPercent: null, eligibleRooms: 0, roomNightsAvailable: 0, roomNightsOccupied: 0 }); view(<DashboardPage />)
  fireEvent.change(screen.getByLabelText('Desde'), { target: { value: metrics.from } }); fireEvent.change(screen.getByLabelText('Hasta (excluido)'), { target: { value: metrics.to } }); fireEvent.click(screen.getByText('Consultar métricas'))
  expect(await screen.findByText('Sin inventario operativo')).toBeInTheDocument()
})
const event: AuditEvent = { id: 'event', actorId: 'admin', action: 'USER_ROLE_CHANGED', resource: 'USER', resourceId: 'target', changes: { oldRole: 'CLIENTE', newRole: 'EMPLEADO' }, occurredAt: '2030-10-10T05:00:00Z', requestId: 'request' }
it('queries audit filters, paginates and shows safe event detail', async () => {
  vi.mocked(api).mockResolvedValue(page([event], 2)); view(<AuditPage />); await screen.findByText(event.action)
  fireEvent.change(screen.getByLabelText('Actor UUID'), { target: { value: 'admin' } }); fireEvent.change(screen.getByLabelText('Acción'), { target: { value: event.action } }); fireEvent.change(screen.getByLabelText('Desde (UTC)'), { target: { value: '2030-10-10T05:00' } })
  fireEvent.click(screen.getByText('Filtrar auditoría')); await waitFor(() => expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('from=2030-10-10T05%3A00%3A00Z'))
  fireEvent.click(await screen.findByText('Ver evento event')); expect(screen.getByRole('region', { name: 'Detalle seguro de auditoría' })).toHaveTextContent('CLIENTE')
  fireEvent.click(screen.getByText('Cerrar evento')); fireEvent.click(screen.getByText('Siguiente')); await waitFor(() => expect(vi.mocked(api).mock.calls.at(-1)![0]).toContain('page=1'))
})
it('shows empty audit results and read errors', async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError(403, 'ACCESS_DENIED', 'Permiso denegado')).mockResolvedValue(page([])); view(<AuditPage />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Permiso denegado'); fireEvent.click(screen.getByText('Reintentar')); expect(await screen.findByText('No hay eventos con estos filtros.')).toBeInTheDocument()
})
