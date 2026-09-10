import { api } from '../auth/api/auth'
import type { Page } from '../rooms/api'

export type Reservation = {
  id: string; code: string; customerId: string; createdBy: string; roomId: string; roomCode: string; roomTypeName: string
  checkIn: string; checkOut: string; guests: number; nights: number
  status: 'CONFIRMED' | 'CHECKED_IN' | 'CHECKED_OUT' | 'CANCELLED' | 'NO_SHOW'
  agreedNightlyRate: number; totalAmount: number; currency: string; cancellationReason: string | null
  cancelledBy: string | null; cancelledAt: string | null; version: number; canCancel: boolean
}
export const statusLabels: Record<Reservation['status'], string> = {
  CONFIRMED: 'Confirmada', CHECKED_IN: 'Ingresada', CHECKED_OUT: 'Finalizada', CANCELLED: 'Cancelada', NO_SHOW: 'No presentado',
}
export type BookingRequest = { roomId: string; checkIn: string; checkOut: string; guests: number; customerId?: string }
export type Customer = { id: string; name: string; email: string }
export const money = (value: number, currency: string) => new Intl.NumberFormat('es-PE', { style: 'currency', currency }).format(value)
export function history(params: Record<string, string | number>) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => { if (value !== '') query.set(key, String(value)) })
  return api<Page<Reservation>>('/reservations?' + query)
}
export const book = (body: BookingRequest, key: string, staff: boolean) => api<Reservation>(staff ? '/staff/reservations' : '/reservations', 'POST', body, key)
