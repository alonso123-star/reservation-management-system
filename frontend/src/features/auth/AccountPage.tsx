import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { z } from 'zod'
import { api, clearSession, errorMessage, type Session, type User } from './api/auth'
import { useAuth } from './useAuth'
import { passwordSchema } from './schemas'

const roles = { CLIENTE: 'Cliente', EMPLEADO: 'Empleado', ADMIN: 'Administrador' }
const date = (value: string) => new Date(value).toLocaleString('es-PE', { dateStyle: 'medium', timeStyle: 'short' })

export function AccountPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const cache = useQueryClient()
  const profile = useQuery({ queryKey: ['profile', user?.id], queryFn: () => api<User>('/users/me'), retry: false })
  const sessions = useQuery({ queryKey: ['sessions', user?.id], queryFn: () => api<Session[]>('/users/me/sessions'), retry: false })
  const [error, setError] = useState('')
  const [revoking, setRevoking] = useState<string | null>(null)
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<z.infer<typeof passwordSchema>>({
    resolver: zodResolver(passwordSchema),
  })
  const me = profile.data ?? user
  if (!me) return null

  return <section className="account-page">
    <p className="eyebrow">MI CUENTA</p>
    <h1>Hola, {me.name}.</h1>
    <p className="account-subtitle">Tu perfil y la seguridad de tu cuenta, en un solo lugar.</p>
    {error && <p className="form-error" role="alert">{error}</p>}
    {profile.error && <p className="form-error" role="alert">{errorMessage(profile.error)}</p>}
    <div className="account-grid">
      <div>
        <section className="auth-card profile-card" aria-labelledby="profile-title">
          <h2 id="profile-title">Tu perfil</h2>
          <dl><dt>Nombre</dt><dd>{me.name}</dd><dt>Correo electrónico</dt><dd>{me.email}</dd><dt>Rol</dt><dd><span className="role-badge">{roles[me.role]}</span></dd></dl>
        </section>
        <section className="auth-card session-card" aria-labelledby="sessions-title">
          <h2 id="sessions-title">Sesiones activas</h2>
          <p className="field-hint">Cierra cualquier sesión que ya no utilices. Cerrar la sesión actual te llevará al acceso.</p>
          {sessions.isPending && <p role="status">Cargando sesiones…</p>}
          {sessions.error && <p className="form-error" role="alert">{errorMessage(sessions.error)}</p>}
          <ul className="session-list">{sessions.data?.map(session => <li key={session.id}>
            <div><strong>{session.current ? 'Esta sesión' : 'Otra sesión'}</strong><span>Iniciada: {date(session.createdAt)}</span><span>Vence: {date(session.expiresAt)}</span></div>
            <button type="button" className="secondary-button" disabled={revoking !== null} onClick={async () => {
              setError(''); setRevoking(session.id)
              try {
                await api<void>('/users/me/sessions/' + session.id, 'DELETE')
                if (session.current) { clearSession(); navigate('/login', { replace: true }) }
                else await cache.invalidateQueries({ queryKey: ['sessions', user?.id] })
              } catch (failure) { setError(errorMessage(failure)) }
              finally { setRevoking(null) }
            }}>{revoking === session.id ? 'Cerrando…' : 'Cerrar sesión'}</button>
          </li>)}</ul>
        </section>
      </div>
      <form className="auth-card password-card" onSubmit={handleSubmit(async values => {
        setError('')
        try {
          await api<void>('/users/me/password', 'PUT', { currentPassword: values.currentPassword, newPassword: values.newPassword })
          reset(); clearSession()
          navigate('/login', { replace: true, state: { notice: 'Contraseña actualizada. Todas tus sesiones se han cerrado.' } })
        } catch (failure) { setError(errorMessage(failure)) }
      })} noValidate>
        <h2>Cambiar contraseña</h2>
        <p className="field-hint">Al cambiarla se cerrarán todas tus sesiones, incluida esta.</p>
        <label htmlFor="currentPassword">Contraseña actual</label>
        <input id="currentPassword" type="password" autoComplete="current-password" {...register('currentPassword')} aria-invalid={!!errors.currentPassword} />
        {errors.currentPassword && <p className="field-error">{errors.currentPassword.message}</p>}
        <label htmlFor="newPassword">Nueva contraseña</label>
        <input id="newPassword" type="password" autoComplete="new-password" {...register('newPassword')} aria-invalid={!!errors.newPassword} />
        {errors.newPassword && <p className="field-error">{errors.newPassword.message}</p>}
        <label htmlFor="confirmPassword">Repite la nueva contraseña</label>
        <input id="confirmPassword" type="password" autoComplete="new-password" {...register('confirmPassword')} aria-invalid={!!errors.confirmPassword} />
        {errors.confirmPassword && <p className="field-error">{errors.confirmPassword.message}</p>}
        <button type="submit" disabled={isSubmitting}>{isSubmitting ? 'Actualizando…' : 'Actualizar contraseña'}</button>
      </form>
    </div>
  </section>
}
