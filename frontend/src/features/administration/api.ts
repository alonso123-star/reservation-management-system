import { api, type Role } from '../auth/api/auth'
export type AdminUser = { id: string; name: string; email: string; role: Role; active: boolean; version: number; createdAt: string; updatedAt: string }
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type AuditEvent = { id: string; actorId: string | null; action: string; resource: string; resourceId: string | null; changes: Record<string, string | boolean>; occurredAt: string; requestId: string }
export type Dashboard = { from: string; to: string; hotelTimeZone: string; currency: string; nights: number; reservationsCreated: number; arrivals: number; departures: number; eligibleRooms: number; roomNightsOccupied: number; roomNightsAvailable: number; occupancyPercent: number | null; approvedPayments: number; refunds: number; netRevenue: number }
export function query<T>(path: string, filters: Record<string, string | number>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) if (value !== '') params.set(key, String(value))
  return api<T>(path + '?' + params)
}
