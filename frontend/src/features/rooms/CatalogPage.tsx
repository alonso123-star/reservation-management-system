import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, publicGet, errorMessage } from '../auth/api/auth'
import { useAuth } from '../auth/useAuth'
import { list, resource, price, statuses, type TypeItem, type RoomItem, type Kind, type OperationalStatus } from './api'
import { TypeForm, RoomForm } from './CatalogForms'
import { TypePicker } from './TypePicker'

type Item = TypeItem | RoomItem
const isRoom = (item: Item): item is RoomItem => 'roomType' in item
function Summary({ item }: { item: Item }) {
  const type = isRoom(item) ? item.roomType : item
  return <><h2>{isRoom(item) ? 'Habitación ' + item.code : item.name}</h2>
    {isRoom(item) && <p>{type.name} · Piso {item.floor}</p>}
    <p>{type.description || 'Sin descripción adicional.'}</p>
    <p>Capacidad: {type.capacity} personas</p><p className="catalog-price">{price(type)} <span>/ noche · tarifa base</span></p>
  </>
}
function StatusForm({ room, done, failed }: { room: RoomItem; done: () => void; failed: (e: unknown) => void }) {
  const [status, setStatus] = useState<OperationalStatus>(room.operationalStatus ?? 'ACTIVE')
  const [pending, setPending] = useState(false)
  return <form className="status-form" onSubmit={async e => {
    e.preventDefault(); setPending(true)
    try { await api('/rooms/' + room.id + '/status', 'PATCH', { version: room.version, operationalStatus: status }); done() }
    catch (error) { failed(error) } finally { setPending(false) }
  }}>
    <label>Estado de {room.code}<select value={status} onChange={e => setStatus(e.target.value as OperationalStatus)}>
      {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label><button disabled={pending || status === room.operationalStatus}>{pending ? 'Guardando…' : 'Guardar estado'}</button>
  </form>
}
export function CatalogPage({ kind, staff = false }: { kind: Kind; staff?: boolean }) {
  const { user } = useAuth()
  const admin = staff && user?.role === 'ADMIN'
  const cache = useQueryClient()
  const [filters, setFilters] = useState<Record<string, string | number>>({})
  const [page, setPage] = useState(0)
  const [typeFilter, setTypeFilter] = useState('')
  const [editor, setEditor] = useState<Item | 'new' | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)
  const [editorRevision, setEditorRevision] = useState(0)
  const results = useQuery({ queryKey: ['catalog', kind, staff, user?.id, filters, page],
    queryFn: () => list<Item>(kind, staff, { ...filters, page, size: filters.size ?? 6 }), retry: false })
  const base = staff ? '/staff/catalog' : '/catalog'
  function apply(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const values = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>
    setFilters({ ...values, ...(kind === 'rooms' ? { roomTypeId: typeFilter } : {}) }); setPage(0)
  }
  async function refresh() { await cache.invalidateQueries({ queryKey: ['catalog'] }) }
  function saved() { setEditor(null); setError(null); setMessage('Cambios guardados correctamente.'); void refresh() }
  async function reloadEditor() {
    if (!editor || editor === 'new') { setEditor(null); return }
    try {
      const current = await api<Item>('/staff/' + resource(kind) + '/' + editor.id)
      setEditor(current); setEditorRevision(v => v + 1); await refresh()
    } catch (failure) { setError(failure) }
  }
  return <section className="catalog-page">
    <p className="eyebrow">{staff ? 'INVENTARIO DEL HOTEL' : 'CONOCE EL HOTEL'}</p>
    <h1>{kind === 'types' ? 'Tipos de habitación' : 'Habitaciones'}</h1>
    <p className="catalog-subtitle">{staff ? 'Consulta el inventario y gestiona las acciones permitidas para tu rol.' : 'Explora las características y tarifas base de nuestro catálogo.'}</p>
    <nav className="catalog-tabs" aria-label="Secciones del catálogo">
      <Link to={base + '/types'} aria-current={kind === 'types' ? 'page' : undefined}>Tipos</Link>
      <Link to={base + '/rooms'} aria-current={kind === 'rooms' ? 'page' : undefined}>Habitaciones</Link>
    </nav>
    <form className="catalog-filters" onSubmit={apply}>
      <label>{kind === 'types' ? 'Buscar por nombre' : 'Buscar por código'}<input name="q" maxLength={kind === 'types' ? 100 : 30} /></label>
      {kind === 'types' ? <>
        <label>Capacidad mínima<input name="minCapacity" type="number" min="1" max="1000" /></label>
        <label>Capacidad máxima<input name="maxCapacity" type="number" min="1" max="1000" /></label>
      </> : <>
        <TypePicker staff={staff} value={typeFilter} onChange={setTypeFilter} label="Filtrar por tipo" />
        <label>Filtrar por piso<input name="floor" type="number" min="-5" max="200" /></label>
      </>}
      {staff && <>
        <label>Actividad<select name="active"><option value="">Todas</option><option value="true">Activos</option><option value="false">Inactivos</option></select></label>
        {kind === 'rooms' && <label>Filtrar por estado<select name="operationalStatus"><option value="">Todos</option>
          {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>}
      </>}
      <label>Orden<select name="sort" defaultValue={kind === 'types' ? 'name,asc' : 'code,asc'}>
        {kind === 'types' ? <><option value="name,asc">Nombre A–Z</option><option value="name,desc">Nombre Z–A</option>
          <option value="basePrice,asc">Tarifa menor primero</option><option value="capacity,desc">Mayor capacidad</option></>
          : <><option value="code,asc">Código ascendente</option><option value="code,desc">Código descendente</option><option value="floor,asc">Piso ascendente</option></>}
      </select></label>
      <label>Por página<select name="size" defaultValue="6"><option value="6">6</option><option value="12">12</option><option value="24">24</option></select></label>
      <button type="submit">Aplicar filtros</button>
    </form>
    {admin && <button className="catalog-create" onClick={() => { setEditor('new'); setError(null); setMessage('') }}>
      {kind === 'types' ? 'Crear tipo' : 'Crear habitación'}</button>}
    {message && <p className="success-message" role="status">{message}</p>}
    {!!error && <div className="form-error" role="alert">{errorMessage(error)}
      <button onClick={() => { setError(null); void refresh() }}>Actualizar listado</button></div>}
    {admin && editor && (kind === 'types' ? <TypeForm key={(editor === 'new' ? 'new' : editor.id) + editorRevision}
      initial={editor === 'new' ? undefined : editor as TypeItem} onSaved={saved} onCancel={() => setEditor(null)} onReload={reloadEditor} />
      : <RoomForm key={(editor === 'new' ? 'new' : editor.id) + editorRevision}
        initial={editor === 'new' ? undefined : editor as RoomItem} onSaved={saved} onCancel={() => setEditor(null)} onReload={reloadEditor} />)}
    {results.isPending && <p role="status">Cargando catálogo…</p>}
    {results.isError && <div className="form-error" role="alert">{errorMessage(results.error)}
      <button onClick={() => { void results.refetch() }}>Reintentar</button></div>}
    {results.data && <>
      <p>{results.data.totalElements} resultados</p>
      {results.data.items.length === 0 && <p className="catalog-empty">No hay elementos que coincidan con estos filtros.</p>}
      <div className="catalog-grid">{results.data.items.map(item => <article className="auth-card" key={item.id}>
        <Summary item={item} />
        {!staff && <Link className="catalog-detail-link" to={base + '/' + kind + '/' + item.id}>Ver detalle</Link>}
        {staff && <>
          <p><span className="role-badge">{item.active ? 'Activo' : 'Inactivo'}</span></p>
          {isRoom(item) && <StatusForm key={item.id + ':' + item.version} room={item} done={saved} failed={setError} />}
          {admin && <div className="catalog-actions">
            <button onClick={() => { setEditor(item); setMessage('') }}>Editar {isRoom(item) ? item.code : item.name}</button>
            <button disabled={pending} onClick={async () => {
              setPending(true); setError(null); setMessage('')
              try { await api('/' + resource(kind) + '/' + item.id, 'PATCH', { version: item.version, active: !item.active }); saved() }
              catch (failure) { setError(failure) } finally { setPending(false) }
            }}>{item.active ? 'Desactivar' : 'Activar'}</button>
          </div>}
        </>}
      </article>)}</div>
      <nav className="catalog-pagination" aria-label="Paginación">
        <button disabled={page === 0 || results.isFetching} onClick={() => setPage(page - 1)}>Anterior</button>
        <span>Página {page + 1} de {Math.max(1, results.data.totalPages)}</span>
        <button disabled={page + 1 >= results.data.totalPages || results.isFetching} onClick={() => setPage(page + 1)}>Siguiente</button>
      </nav>
    </>}
  </section>
}
export function CatalogDetail({ kind }: { kind: Kind }) {
  const { id } = useParams()
  const detail = useQuery({ queryKey: ['catalog', 'detail', kind, id], queryFn: () => publicGet<Item>('/' + resource(kind) + '/' + id), retry: false })
  return <section className="catalog-page">
    <Link to={'/catalog/' + kind}>Volver al catálogo</Link>
    {detail.isPending && <p role="status">Cargando detalle…</p>}
    {detail.error && <p className="form-error" role="alert">{errorMessage(detail.error)}</p>}
    {detail.data && <article className="auth-card catalog-detail"><Summary item={detail.data} /></article>}
  </section>
}
