import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { api, ApiError, errorMessage } from '../auth/api/auth'
import { statuses, type TypeItem, type RoomItem } from './api'
import { TypePicker } from './TypePicker'

const integer = (min: number, max: number) => z.string().regex(/^-?\d+$/, 'Introduce un número entero.')
  .refine(v => Number(v) >= min && Number(v) <= max, `Debe estar entre ${min} y ${max}.`)
const typeSchema = z.object({
  name: z.string().trim().min(1, 'Introduce el nombre.').max(100),
  description: z.string().max(2000),
  capacity: integer(1, 1000),
  basePrice: z.string().regex(/^\d{1,10}(\.\d{1,2})?$/, 'Usa hasta 10 enteros y 2 decimales.')
    .refine(v => Number(v) > 0, 'La tarifa debe ser mayor que cero.'),
  active: z.boolean(),
})
const roomSchema = z.object({
  code: z.string().trim().min(1, 'Introduce el código.').max(30),
  roomTypeId: z.string().uuid('Selecciona un tipo.'),
  floor: integer(-5, 200),
  operationalStatus: z.enum(['ACTIVE', 'MAINTENANCE', 'OUT_OF_SERVICE']),
  active: z.boolean(),
})
type EditorProps<T> = { initial?: T; onSaved: () => void; onCancel: () => void; onReload: () => Promise<void> }
function Failure({ error, reload }: { error: unknown; reload: () => Promise<void> }) {
  return error ? <div className="form-error" role="alert">{errorMessage(error)}
    {error instanceof ApiError && error.status === 409 && <button type="button" onClick={() => { void reload() }}>Recargar registro</button>}
  </div> : null
}
export function TypeForm({ initial, onSaved, onCancel, onReload }: EditorProps<TypeItem>) {
  const [error, setError] = useState<unknown>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<z.infer<typeof typeSchema>>({
    resolver: zodResolver(typeSchema), defaultValues: {
      name: initial?.name ?? '', description: initial?.description ?? '', capacity: String(initial?.capacity ?? 1),
      basePrice: initial ? String(initial.basePrice) : '', active: initial?.active ?? true,
    },
  })
  return <form className="auth-card catalog-editor" noValidate onSubmit={handleSubmit(async values => {
    setError(null)
    try {
      await api('/room-types' + (initial ? '/' + initial.id : ''), initial ? 'PATCH' : 'POST', {
        ...values, capacity: Number(values.capacity), ...(initial ? { version: initial.version } : {}),
      }); onSaved()
    } catch (failure) { setError(failure) }
  })}>
    <h2>{initial ? 'Editar tipo' : 'Crear tipo'}</h2>
    <Failure error={error} reload={onReload} />
    <label>Nombre del tipo<input {...register('name')} aria-invalid={!!errors.name} /></label>
    {errors.name && <p role="alert">{errors.name.message}</p>}
    <label>Descripción<textarea {...register('description')} rows={3} /></label>
    {errors.description && <p role="alert">{errors.description.message}</p>}
    <label>Capacidad<input type="number" min="1" max="1000" {...register('capacity')} /></label>
    {errors.capacity && <p role="alert">{errors.capacity.message}</p>}
    <label>Tarifa base por noche<input inputMode="decimal" {...register('basePrice')} /></label>
    {errors.basePrice && <p role="alert">{errors.basePrice.message}</p>}
    <label className="checkbox-label"><input type="checkbox" {...register('active')} />Tipo activo</label>
    <div className="catalog-actions"><button type="submit" disabled={isSubmitting}>{isSubmitting ? 'Guardando…' : 'Guardar tipo'}</button>
      <button type="button" onClick={onCancel} disabled={isSubmitting}>Cancelar edición</button></div>
  </form>
}
export function RoomForm({ initial, onSaved, onCancel, onReload }: EditorProps<RoomItem>) {
  const [error, setError] = useState<unknown>(null)
  const { register, handleSubmit, control, setValue, formState: { errors, isSubmitting } } = useForm<z.infer<typeof roomSchema>>({
    resolver: zodResolver(roomSchema), defaultValues: {
      code: initial?.code ?? '', floor: String(initial?.floor ?? 0), roomTypeId: initial?.roomType.id ?? '',
      operationalStatus: initial?.operationalStatus ?? 'ACTIVE', active: initial?.active ?? true,
    },
  })
  const roomTypeId = useWatch({ control, name: 'roomTypeId' })
  return <form className="auth-card catalog-editor" noValidate onSubmit={handleSubmit(async values => {
    setError(null)
    try {
      await api('/rooms' + (initial ? '/' + initial.id : ''), initial ? 'PATCH' : 'POST', {
        ...values, floor: Number(values.floor), ...(initial ? { version: initial.version } : {}),
      }); onSaved()
    } catch (failure) { setError(failure) }
  })}>
    <h2>{initial ? 'Editar habitación' : 'Crear habitación'}</h2>
    <Failure error={error} reload={onReload} />
    <label>Código de habitación<input {...register('code')} aria-invalid={!!errors.code} /></label>
    {errors.code && <p role="alert">{errors.code.message}</p>}
    <TypePicker staff value={roomTypeId} initialName={initial?.roomType.name}
      onChange={value => setValue('roomTypeId', value, { shouldValidate: true })} />
    {errors.roomTypeId && <p role="alert">{errors.roomTypeId.message}</p>}
    <label>Piso<input type="number" min="-5" max="200" {...register('floor')} /></label>
    {errors.floor && <p role="alert">{errors.floor.message}</p>}
    <label>Estado operativo<select {...register('operationalStatus')}>
      {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label>
    <label className="checkbox-label"><input type="checkbox" {...register('active')} />Habitación activa</label>
    <div className="catalog-actions"><button type="submit" disabled={isSubmitting}>{isSubmitting ? 'Guardando…' : 'Guardar habitación'}</button>
      <button type="button" onClick={onCancel} disabled={isSubmitting}>Cancelar edición</button></div>
  </form>
}
