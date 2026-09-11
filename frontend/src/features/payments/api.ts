import { api } from '../auth/api/auth'
import type { Page } from '../rooms/api'

export type Refund = { id: string; paymentId: string; amount: number; currency: string; reason: string; actorId: string; createdAt: string }
export type Payment = { id: string; reservationId: string; amount: number; currency: string; result: 'APPROVED' | 'DECLINED'; simulatedReference: string; actorId: string; createdAt: string; refund: Refund | null }
export type PaymentHistory = { settlement: 'UNPAID' | 'PAID' | 'REFUNDED'; amountDue: number; currency: string; canPay: boolean; attempts: Page<Payment> }
export const paymentHistory = (id: string, page: number) => api<PaymentHistory>(`/reservations/${id}/payments?page=${page}&size=6&sort=createdAt,desc`)
export const pay = (id: string, key: string) => api<Payment>(`/reservations/${id}/payments`, 'POST', {}, key)
