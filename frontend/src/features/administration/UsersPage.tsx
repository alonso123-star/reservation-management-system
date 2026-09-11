import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { api, clearSession, errorMessage, type Role } from '../auth/api/auth'
import { useAuth } from '../auth/useAuth'
import { registerSchema } from '../auth/schemas'
import { AdminLayout, Pagination } from './AdminLayout'
import { query, type AdminUser, type Page } from './api'

export function UsersPage() { return <AdminLayout><Users /></AdminLayout> }
function Users() {
  const { user } = useAuth()
  const [filters, setFilters] = useState({ q: '', role: '', active: '' })
  const [page, setPage] = useState(0)
  const result = useQuery({ queryKey: ['admin-users', user?.id, filters, page], retry: false,
    queryFn: () => query<Page<AdminUser>>('/users', { ...filters, page, size: 10, sort: 'name,asc' }) })
  return <><h2>Usuarios</h2><Link to="/admin/users/new">Crear usuario</Link>
    <form className="catalog-filters" onSubmit={e => {
      e.preventDefault(); const data = new FormData(e.currentTarget)
      setFilters({ q: String(data.get('q')), role: String(data.get('role')), active: String(data.get('active')) }); setPage(0)
    }}>
      <label>Nombre o correo<input name="q" maxLength={100} /></label>
      <label>Rol<select name="role"><option value="">Todos</option>{['CLIENTE', 'EMPLEADO', 'ADMIN'].map(r => <option key={r}>{r}</option>)}</select></label>
      <label>Estado<select name="active"><option value="">Todos</option><option value="true">Activo</option><option value="false">Inactivo</option></select></label><button>Buscar usuarios</button>
    </form>
    {result.isPending && <p role="status">Cargando usuarios…</p>}
    {result.error && <p role="alert">{errorMessage(result.error)} <button onClick={() => void result.refetch()}>Reintentar</button></p>}
    {result.data && <>{result.data.items.length === 0 && <p>No hay usuarios con estos filtros.</p>}
      <div className="catalog-grid">{result.data.items.map(u => <article className="feature-card" key={u.id}><h3>{u.name}</h3><p>{u.email}</p><p>{u.role} · {u.active ? 'Activo' : 'Inactivo'}</p><Link to={'/admin/users/' + u.id}>Consultar {u.name}</Link></article>)}</div>
      <Pagination page={page} totalPages={result.data.totalPages} busy={result.isFetching} onPage={setPage} /></>}
  </>
}
const createSchema = registerSchema.safeExtend({ role: z.enum(['CLIENTE', 'EMPLEADO', 'ADMIN']) })
export function CreateUserPage() { return <AdminLayout><CreateUser /></AdminLayout> }
function CreateUser() {
  const cache = useQueryClient()
  const form = useForm<z.infer<typeof createSchema>>({ resolver: zodResolver(createSchema), defaultValues: { role: 'CLIENTE' } })
  const create = useMutation({ mutationFn: (values: z.infer<typeof createSchema>) => api<AdminUser>('/users', 'POST', { name: values.name, email: values.email, password: values.password, role: values.role }),
    onSuccess: async () => { form.reset(); await cache.invalidateQueries({ queryKey: ['admin-users'] }) } })
  return <><h2>Crear usuario</h2><p>Define una contraseña individual. No se enviará correo; comunica el acceso por un canal adecuado.</p>
    {create.data ? <p role="status">Usuario creado. <Link to={'/admin/users/' + create.data.id}>Consultar usuario creado</Link></p> :
      <form className="account-form" onSubmit={form.handleSubmit(values => create.mutate(values))}>
        <label>Nombre<input {...form.register('name')} autoComplete="off" /></label>
        <label>Correo<input {...form.register('email')} type="email" autoComplete="off" /></label>
        <label>Contraseña<input {...form.register('password')} type="password" autoComplete="new-password" /></label>
        <label>Confirmar contraseña<input {...form.register('confirmPassword')} type="password" autoComplete="new-password" /></label>
        <label>Rol inicial<select {...form.register('role')}>{['CLIENTE', 'EMPLEADO', 'ADMIN'].map(r => <option key={r}>{r}</option>)}</select></label>
        {Object.entries(form.formState.errors).map(([key, error]) => <p role="alert" key={key}>{error.message}</p>)}
        {create.error && <p role="alert">{errorMessage(create.error)} Consulta el listado antes de repetir una creación con respuesta incierta.</p>}
        <button disabled={create.isPending}>{create.isPending ? 'Creando…' : 'Crear usuario'}</button>
      </form>}
  </>
}
export function AdminUserDetailPage() { return <AdminLayout><Detail /></AdminLayout> }
function Detail() {
  const { id } = useParams(); const { user } = useAuth()
  const result = useQuery({ queryKey: ['admin-users', user?.id, 'detail', id], retry: false, queryFn: () => api<AdminUser>('/users/' + id) })
  return <><h2>Detalle de usuario</h2>{result.isPending && <p role="status">Cargando usuario…</p>}
    {result.error && <p role="alert">{errorMessage(result.error)}</p>}
    {result.data && <UserEditor key={result.data.id + ':' + result.data.version} value={result.data} />}
    <button onClick={() => void result.refetch()} disabled={result.isFetching}>Actualizar usuario</button></>
}
function UserEditor({ value: u }: { value: AdminUser }) {
  const { user } = useAuth(); const cache = useQueryClient()
  const [role, setRole] = useState<Role>(u.role)
  const [action, setAction] = useState<'role' | 'status' | null>(null)
  const change = useMutation({ mutationFn: () => api<AdminUser>('/users/' + u.id + '/' + action, 'PATCH', action === 'role' ? { role, version: u.version } : { active: !u.active, version: u.version }),
    onSuccess: async changed => {
      if (changed.id === user?.id && (changed.role !== user.role || !changed.active)) { clearSession(); cache.clear(); return }
      cache.setQueryData(['admin-users', user?.id, 'detail', u.id], changed)
      await Promise.all([cache.invalidateQueries({ queryKey: ['admin-users'] }), cache.invalidateQueries({ queryKey: ['admin-audit'] })])
      setAction(null)
    } })
  return <article className="feature-card"><h3>{u.name}</h3><p>{u.email} · {u.role} · {u.active ? 'Activo' : 'Inactivo'}</p><p>Creación: {u.createdAt}</p>
    <label>Nuevo rol<select value={role} disabled={change.isPending || action !== null} onChange={e => setRole(e.target.value as Role)}>{['CLIENTE', 'EMPLEADO', 'ADMIN'].map(r => <option key={r}>{r}</option>)}</select></label>
    <button disabled={change.isPending || action !== null || role === u.role} onClick={() => setAction('role')}>Cambiar rol</button>
    <button disabled={change.isPending || action !== null} onClick={() => setAction('status')}>{u.active ? 'Desactivar usuario' : 'Activar usuario'}</button>
    {action && <div role="group" aria-label="Confirmar cambio administrativo"><p>{u.name}: {action === 'role' ? `${u.role} → ${role}` : u.active ? 'Desactivar cuenta' : 'Activar cuenta'}.</p>
      <p>Se invalidarán las sesiones existentes. Debe permanecer al menos un ADMIN activo.</p>
      {u.id === user?.id && <p>Estás modificando tu propia cuenta; se cerrará tu sesión si cambian tus permisos o desactivas tu cuenta.</p>}
      {change.error && <p role="alert">{errorMessage(change.error)} Actualiza el usuario antes de volver a intentar.</p>}
      <button disabled={change.isPending || change.isError} onClick={() => change.mutate()}>{change.isPending ? 'Guardando…' : 'Confirmar cambio'}</button>
      <button disabled={change.isPending} onClick={() => { setAction(null); change.reset() }}>Volver</button>
    </div>}
  </article>
}
