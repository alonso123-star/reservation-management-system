import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../auth/api/auth'
import { useAuth } from '../auth/useAuth'
import type { Page } from '../rooms/api'
import type { Customer } from './api'

export function CustomerPicker({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const { user } = useAuth()
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Customer | null>(null)
  const customers = useQuery({ queryKey: ['customers', user?.id, q, page], retry: false,
    queryFn: () => api<Page<Customer>>('/staff/customers?' + new URLSearchParams({ q, page: String(page), size: '20' })) })
  return <div className="type-picker">
    <label>Buscar cliente<input maxLength={100} value={q} onChange={e => { setQ(e.target.value); setPage(0) }} /></label>
    <label>Cliente<select value={value} onChange={e => {
      const chosen = customers.data?.items.find(c => c.id === e.target.value)
      setSelected(chosen ?? null); onChange(e.target.value)
    }}><option value="">Selecciona un cliente</option>
      {selected && <option value={selected.id}>{selected.name} · {selected.email}</option>}
      {customers.data?.items.filter(c => c.id !== selected?.id).map(c => <option key={c.id} value={c.id}>{c.name} · {c.email}</option>)}
    </select></label>
    {customers.isPending && <p role="status">Buscando clientes…</p>}
    {customers.error && <p role="alert">{errorMessage(customers.error)}</p>}
    {customers.data && customers.data.totalPages > 1 && <div className="picker-pages">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Clientes anteriores</button>
      <button type="button" disabled={page + 1 >= customers.data.totalPages} onClick={() => setPage(page + 1)}>Más clientes</button>
    </div>}
  </div>
}
