import { publicGet } from '../auth/api/auth'
import type { Page, RoomItem } from './api'
import type { AvailabilityForm } from './availability-schema'

export type AvailabilityItem = Pick<RoomItem, 'id' | 'code' | 'floor' | 'roomType'> & { nights: number; estimatedTotal: number }
export function searchAvailability(filter: AvailabilityForm, page: number) {
  const params = new URLSearchParams()
  Object.entries({ ...filter, page: String(page) }).forEach(([key, value]) => {
    if (value !== '') params.set(key, value)
  })
  return publicGet<Page<AvailabilityItem>>('/rooms/availability?' + params.toString())
}
