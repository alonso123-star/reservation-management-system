import { test as base, expect, type Page } from '@playwright/test'
import { resetE2E } from '../../scripts/sandbox.mjs'
import { addresses, demoPassword, seed, shift } from '../../scripts/demo-data.mjs'

export { expect, addresses, demoPassword, shift }
export const test = base.extend<{ data: Awaited<ReturnType<typeof seed>>; seedStays: boolean }>({
  seedStays: [false, { option: true }],
  data: [async ({ seedStays }, use) => {
    const env = resetE2E()
    const data = await seed(env, seedStays)
    await use(data)
  }, { auto: true }],
})

export async function login(page: Page, email: string, password = demoPassword) {
  await page.goto('/login')
  await page.getByLabel('Correo electrónico', { exact: true }).fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Entrar a mi cuenta' }).click()
  await expect(page).toHaveURL(/\/account$/)
  await expect(page.getByRole('heading', { name: 'Tu perfil', exact: true })).toBeVisible()
}
export async function search(page: Page, date: string) {
  await page.goto('/availability')
  await page.getByLabel('Entrada', { exact: true }).fill(date)
  await page.getByLabel('Salida', { exact: true }).fill(shift(date, 2))
  await page.getByLabel('Huéspedes', { exact: true }).fill('2')
  await page.getByRole('button', { name: 'Buscar habitaciones' }).click()
  await expect(page.getByText(/habitaciones encontradas/)).toBeVisible()
}
export async function book(page: Page, date: string) {
  await search(page, date)
  const room = page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Habitación 101', exact: true }) })
  await room.getByRole('button', { name: 'Reservar', exact: true }).click()
  await room.getByRole('button', { name: 'Confirmar reserva', exact: true }).click()
  await room.getByRole('link', { name: 'Ver reserva', exact: true }).click()
  await expect(page.getByText('Estado: Confirmada', { exact: true })).toBeVisible()
  return new URL(page.url()).pathname
}
export async function pay(page: Page) {
  await page.getByRole('button', { name: 'Pagar importe completo (simulado)', exact: true }).click()
  await expect(page.getByText(/Intento rechazado:/)).toBeVisible()
  await page.getByRole('button', { name: 'Nuevo intento simulado', exact: true }).click()
  await expect(page.getByText('Estado de pago: Pagada', { exact: true })).toBeVisible()
}
export async function reception(page: Page, action: 'Check-in' | 'Check-out' | 'No-show', status: string) {
  await page.getByRole('button', { name: action, exact: true }).click()
  await page.getByRole('button', { name: 'Confirmar operación', exact: true }).click()
  await expect(page.getByText('Estado: ' + status, { exact: true })).toBeVisible()
}
