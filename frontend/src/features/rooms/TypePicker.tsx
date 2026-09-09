import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { errorMessage } from '../auth/api/auth'
import { list, type TypeItem } from './api'

export function TypePicker({ staff, value, onChange, initialName, label = 'Tipo de habitación' }: {
  staff: boolean; value: string; onChange: (id: string) => void; initialName?: string; label?: string
}) {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState({ id: value, name: initialName ?? '' })
  const types = useQuery({ queryKey: ['catalog', 'picker', staff, search, page],
    queryFn: () => list<TypeItem>('types', staff, { q: search, page, size: 20, sort: 'name,asc' }), retry: false })
  return <div className="type-picker">
    <label>Buscar tipos<input value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} maxLength={100} /></label>
    <label>{label}<select name="roomTypeId" value={value} onChange={e => {
      const chosen = types.data?.items.find(t => t.id === e.target.value)
      setSelected({ id: e.target.value, name: chosen?.name ?? '' }); onChange(e.target.value)
    }}>
      <option value="">Selecciona un tipo</option>
      {selected.id && selected.id === value && <option value={selected.id}>{selected.name || initialName}</option>}
      {types.data?.items.filter(t => t.id !== selected.id || selected.id !== value).map(t =>
        <option key={t.id} value={t.id}>{t.name}{t.active === false ? ' (inactivo)' : ''}</option>)}
    </select></label>
    {types.isPending && <p role="status">Cargando tipos…</p>}
    {types.error && <p role="alert">{errorMessage(types.error)}</p>}
    {types.data && types.data.totalPages > 1 && <div className="picker-pages">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Tipos anteriores</button>
      <span>{page + 1} / {types.data.totalPages}</span>
      <button type="button" disabled={page + 1 >= types.data.totalPages} onClick={() => setPage(page + 1)}>Más tipos</button>
    </div>}
  </div>
}
