import { api, publicGet } from '../auth/api/auth'

export type OperationalStatus = 'ACTIVE' | 'MAINTENANCE' | 'OUT_OF_SERVICE'
export type TypeItem = { id: string; name: string; description: string; capacity: number; basePrice: number; currency: string; active?: boolean; version?: number }
export type RoomItem = { id: string; code: string; floor: number; roomType: TypeItem; operationalStatus?: OperationalStatus; active?: boolean; version?: number }
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type Kind = 'types' | 'rooms'
export const resource = (kind: Kind) => kind === 'types' ? 'room-types' : 'rooms'
export const statuses: Record<OperationalStatus, string> = { ACTIVE: 'Operativa', MAINTENANCE: 'En mantenimiento', OUT_OF_SERVICE: 'Fuera de servicio' }
export function list<T>(kind: Kind, staff: boolean, params: Record<string, string | number>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => { if (value !== '') search.set(key, String(value)) })
  const path = (staff ? '/staff/' : '/') + resource(kind) + '?' + search.toString()
  return staff ? api<Page<T>>(path) : publicGet<Page<T>>(path)
}
export const price = (type: TypeItem) => new Intl.NumberFormat('es-PE', { style: 'currency', currency: type.currency }).format(type.basePrice)
