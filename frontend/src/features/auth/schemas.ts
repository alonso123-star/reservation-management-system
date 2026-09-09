import { z } from 'zod'

const email = z.string().trim().email('Introduce un correo válido.').max(254)
export const password = z.string().min(12, 'Usa al menos 12 caracteres.')
  .refine(value => new TextEncoder().encode(value).length <= 72, 'La contraseña no puede superar 72 bytes UTF-8.')

export const loginSchema = z.object({
  email,
  password: z.string().min(1, 'Introduce tu contraseña.'),
})

export const registerSchema = z.object({
  name: z.string().trim().min(1, 'Introduce tu nombre.').max(100),
  email,
  password,
  confirmPassword: z.string(),
}).refine(value => value.password === value.confirmPassword, {
  message: 'Las contraseñas no coinciden.', path: ['confirmPassword'],
})

export const passwordSchema = z.object({
  currentPassword: z.string().min(1, 'Introduce tu contraseña actual.'),
  newPassword: password,
  confirmPassword: z.string(),
}).refine(value => value.newPassword === value.confirmPassword, {
  message: 'Las contraseñas no coinciden.', path: ['confirmPassword'],
})
