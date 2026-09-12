import { test, expect, addresses, demoPassword, shift, login, search, book, pay, reception } from './fixtures'

test('registro → login → disponibilidad → reserva → pago → check-in → check-out', async ({ page, data }) => {
  const email = 'journey@example.test'
  await page.goto('/register')
  await page.getByLabel('Nombre completo', { exact: true }).fill('Huésped E2E')
  await page.getByLabel('Correo electrónico', { exact: true }).fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(demoPassword)
  await page.getByLabel('Repite la contraseña', { exact: true }).fill(demoPassword)
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
  await expect(page.getByText('Tu cuenta está creada. Ya puedes iniciar sesión.')).toBeVisible()
  await login(page, email)
  const path = await book(page, data.today)
  await pay(page)
  await expect(page.getByRole('button', { name: 'Check-in', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Salir', exact: true }).click()
  await login(page, addresses.employee)
  await page.getByRole('link', { name: 'Recepción', exact: true }).click()
  await page.getByRole('link', { name: 'Consultar huésped, pago y acciones', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(path + '$'))
  await reception(page, 'Check-in', 'Ingresada')
  await reception(page, 'Check-out', 'Finalizada')
  await expect(page.getByText('Estado de pago: Pagada', { exact: true })).toBeVisible()
  await expect(page.getByText(/Reembolso completo:/)).toHaveCount(0)
  await search(page, data.today)
  await expect(page.getByRole('heading', { name: 'Habitación 101', exact: true })).toHaveCount(0)
})

test('cancelación pagada: historial, refund completo y disponibilidad restaurada', async ({ page, data }) => {
  await login(page, addresses.client)
  const date = shift(data.today, 10), path = await book(page, date)
  await pay(page)
  await search(page, date)
  await expect(page.getByRole('heading', { name: 'Habitación 101', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Mis reservas', exact: true }).click()
  await page.getByRole('link', { name: 'Ver detalle de reserva', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(path + '$'))
  await page.getByLabel('Motivo de cancelación').fill('Cancelación sintética E2E')
  await page.getByRole('button', { name: 'Confirmar cancelación' }).click()
  await expect(page.getByText('Estado: Cancelada', { exact: true })).toBeVisible()
  await expect(page.getByText('Estado de pago: Reembolsada', { exact: true })).toBeVisible()
  await expect(page.getByText(/Reembolso completo:/)).toHaveCount(1)
  await search(page, date)
  await expect(page.getByRole('heading', { name: 'Habitación 101', exact: true })).toBeVisible()
})

test('rutas protegidas y catálogo público sin sesión', async ({ page }) => {
  for (const path of ['/account', '/reservations', '/staff/reception', '/admin/users']) {
    await page.goto(path); await expect(page).toHaveURL(/\/login$/)
  }
  await page.goto('/')
  await expect(page.getByText('Servicios disponibles', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Catálogo', exact: true }).click()
  for (const name of ['Standard', 'Deluxe', 'Suite']) await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
})

for (const role of ['client', 'employee'] as const) test(`${role}: restricciones de administración y recepción`, async ({ page }) => {
  await login(page, addresses[role])
  for (const path of ['/admin/users', '/admin/users/new', '/admin/dashboard', '/admin/audit-events']) {
    await page.goto(path); await expect(page.getByRole('heading', { name: 'Acceso restringido' })).toBeVisible()
  }
  await page.goto('/staff/reception')
  await expect(page.getByRole('heading', { name: role === 'client' ? 'Acceso restringido' : 'Recepción', exact: true })).toBeVisible()
})

test('sesión persiste al recargar, se revoca desde la UI y logout impide volver', async ({ page }) => {
  await login(page, addresses.client)
  await page.reload() // Real HttpOnly refresh cookie; no storageState or fake JWT.
  await expect(page.getByRole('heading', { name: 'Tu perfil', exact: true })).toBeVisible()
  await expect(page.getByText('Esta sesión', { exact: true })).toHaveCount(1)
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/account'); await expect(page).toHaveURL(/\/login$/)
  await login(page, addresses.client)
  await page.getByRole('button', { name: 'Salir', exact: true }).click()
  await page.goto('/reservations'); await expect(page).toHaveURL(/\/login$/)
})

test('ownership: otro cliente no ve el detalle ni los pagos de una reserva ajena', async ({ page, data }) => {
  await login(page, addresses.client)
  const path = await book(page, shift(data.today, 10))
  await page.getByRole('button', { name: 'Salir', exact: true }).click()
  await page.goto('/register')
  await page.getByLabel('Nombre completo').fill('Otro huésped E2E')
  await page.getByLabel('Correo electrónico').fill('other@example.test')
  await page.getByLabel('Contraseña', { exact: true }).fill(demoPassword)
  await page.getByLabel('Repite la contraseña').fill(demoPassword)
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await login(page, 'other@example.test')
  const response = page.waitForResponse(r => r.url().endsWith('/api/v1' + path) && r.request().method() === 'GET')
  await page.goto(path)
  expect((await response).status()).toBe(404)
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('region', { name: 'Pagos simulados', exact: true })).toHaveCount(0)
})
