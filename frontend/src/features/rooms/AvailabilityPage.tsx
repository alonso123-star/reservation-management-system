import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { errorMessage } from '../auth/api/auth'
import { price } from './api'
import { searchAvailability } from './availability-api'
import { availabilitySchema, type AvailabilityForm } from './availability-schema'
import { TypePicker } from './TypePicker'

export function AvailabilityPage() {
  const [search, setSearch] = useState<{ filter: AvailabilityForm; attempt: number } | null>(null)
  const [page, setPage] = useState(0)
  const { register, control, setValue, handleSubmit, formState: { errors } } = useForm<AvailabilityForm>({
    resolver: zodResolver(availabilitySchema),
    defaultValues: { checkIn: '', checkOut: '', guests: '2', roomTypeId: '', minPrice: '', maxPrice: '', sort: 'code,asc', size: '6' },
  })
  const roomTypeId = useWatch({ control, name: 'roomTypeId' })
  const result = useQuery({ queryKey: ['availability', search, page], enabled: search !== null,
    queryFn: () => searchAvailability(search!.filter, page), retry: false, staleTime: 0 })
  return <section className="catalog-page">
    <h1>Busca tu estancia</h1>
    <p className="catalog-subtitle">Encuentra habitaciones según tus fechas, huéspedes y presupuesto.</p>
    <p className="catalog-subtitle">Resultados orientativos del catálogo. Por ahora no se descuenta la ocupación por reservas; esta consulta no confirma ni bloquea una habitación.</p>
    <form className="catalog-filters" noValidate onSubmit={handleSubmit(filter => {
      setPage(0); setSearch(previous => ({ filter, attempt: (previous?.attempt ?? 0) + 1 }))
    })}>
      <label>Entrada<input type="date" required {...register('checkIn')} aria-invalid={!!errors.checkIn} /></label>
      <label>Salida<input type="date" required {...register('checkOut')} aria-invalid={!!errors.checkOut} /></label>
      <label>Huéspedes<input type="number" min="1" step="1" required {...register('guests')} aria-invalid={!!errors.guests} /></label>
      <TypePicker staff={false} value={roomTypeId} onChange={id => setValue('roomTypeId', id, { shouldValidate: true })} />
      <label>Precio mínimo por noche<input type="number" min="0" step="0.01" {...register('minPrice')} aria-invalid={!!errors.minPrice} /></label>
      <label>Precio máximo por noche<input type="number" min="0" step="0.01" {...register('maxPrice')} aria-invalid={!!errors.maxPrice} /></label>
      <label>Ordenar por<select {...register('sort')}>
        <option value="code,asc">Código</option><option value="basePrice,asc">Menor precio</option>
        <option value="basePrice,desc">Mayor precio</option><option value="capacity,asc">Menor capacidad</option>
        <option value="capacity,desc">Mayor capacidad</option>
      </select></label>
      <label>Por página<select {...register('size')}><option>6</option><option>12</option><option>24</option></select></label>
      <button type="submit" className="primary-button" disabled={result.isFetching}>Buscar habitaciones</button>
      {Object.entries(errors).map(([field, error]) => <p className="form-error" role="alert" key={field}>{error.message}</p>)}
    </form>
    {!search && <p className="catalog-empty">Introduce fechas y huéspedes para iniciar la búsqueda.</p>}
    {search && <p>Búsqueda: {search.filter.checkIn} al {search.filter.checkOut} · {search.filter.guests} huéspedes</p>}
    {search && result.isFetching && <p role="status">Buscando habitaciones…</p>}
    {result.error && <div role="alert"><p>{errorMessage(result.error)}</p>
      <button type="button" onClick={() => void result.refetch()}>Reintentar</button></div>}
    {result.data && !result.error && <>
      <p role="status">{result.data.totalElements} habitaciones encontradas</p>
      {result.data.items.length === 0 && <p className="catalog-empty">No hay habitaciones que coincidan con tu búsqueda.</p>}
      <div className="catalog-grid">{result.data.items.map(room => <article className="feature-card" key={room.id}>
        <h2>Habitación {room.code}</h2><h3>{room.roomType.name}</h3><p>{room.roomType.description}</p>
        <p>Hasta {room.roomType.capacity} huéspedes · Piso {room.floor}</p>
        <p className="catalog-price">{price(room.roomType)} <span>por noche · {room.roomType.currency}</span></p>
        <p>{room.nights} {room.nights === 1 ? 'noche' : 'noches'}</p>
        <p className="catalog-price">Total estimado: {new Intl.NumberFormat('es-PE', { style: 'currency', currency: room.roomType.currency }).format(room.estimatedTotal)}</p>
        <Link className="catalog-detail-link" to={'/catalog/rooms/' + room.id}>Ver habitación</Link>
      </article>)}</div>
      <nav className="catalog-pagination" aria-label="Páginas de resultados">
        <button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>Anterior</button>
        <span>Página {result.data.page + 1} de {Math.max(1, result.data.totalPages)}</span>
        <button disabled={page + 1 >= result.data.totalPages || result.isFetching} onClick={() => setPage(page + 1)}>Siguiente</button>
      </nav>
    </>}
  </section>
}
