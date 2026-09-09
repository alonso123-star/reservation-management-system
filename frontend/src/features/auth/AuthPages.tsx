import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { z } from 'zod'
import { errorMessage, login, register as registerClient } from './api/auth'
import { useAuth } from './useAuth'
import { loginSchema, registerSchema } from './schemas'

export function LoginPage() {
  const { user, ready } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState('')
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<z.infer<typeof loginSchema>>({
    resolver: zodResolver(loginSchema),
  })
  if (!ready) return <p className="account-loading" role="status">Recuperando tu sesión…</p>
  if (user) return <Navigate to="/account" replace />

  return <section className="auth-page">
    <div className="auth-intro"><p className="eyebrow">BIENVENIDO DE NUEVO</p><h1>Tu próxima estancia<br />empieza aquí.</h1><p>Accede a tu cuenta para gestionar tu perfil y la seguridad de tus sesiones.</p></div>
    <form className="auth-card" onSubmit={handleSubmit(async values => {
      setError('')
      try { await login(values); navigate('/account', { replace: true }) }
      catch (failure) { setError(errorMessage(failure)) }
    })} noValidate>
      <h2>Iniciar sesión</h2>
      {location.state?.notice && <p className="success-message" role="status">{String(location.state.notice)}</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
      <label htmlFor="email">Correo electrónico</label>
      <input id="email" type="email" autoComplete="username" {...register('email')} aria-invalid={!!errors.email} aria-describedby={errors.email ? 'email-error' : undefined} />
      {errors.email && <p id="email-error" className="field-error">{errors.email.message}</p>}
      <label htmlFor="password">Contraseña</label>
      <input id="password" type="password" autoComplete="current-password" {...register('password')} aria-invalid={!!errors.password} aria-describedby={errors.password ? 'password-error' : undefined} />
      {errors.password && <p id="password-error" className="field-error">{errors.password.message}</p>}
      <button type="submit" disabled={isSubmitting}>{isSubmitting ? 'Iniciando sesión…' : 'Entrar a mi cuenta'}</button>
      <p className="form-footer">¿Es tu primera visita? <Link to="/register">Crea tu cuenta</Link></p>
    </form>
  </section>
}

export function RegisterPage() {
  const { user, ready } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<z.infer<typeof registerSchema>>({
    resolver: zodResolver(registerSchema),
  })
  if (!ready) return <p className="account-loading" role="status">Recuperando tu sesión…</p>
  if (user) return <Navigate to="/account" replace />

  return <section className="auth-page">
    <div className="auth-intro"><p className="eyebrow">UN ESPACIO PARA TI</p><h1>Cada viaje comienza<br />con un primer paso.</h1><p>Crea tu cuenta de cliente. Tendrás un espacio propio para tu perfil y tus próximas estancias.</p></div>
    <form className="auth-card" onSubmit={handleSubmit(async values => {
      setError('')
      try {
        await registerClient({ name: values.name, email: values.email, password: values.password })
        navigate('/login', { replace: true, state: { notice: 'Tu cuenta está creada. Ya puedes iniciar sesión.' } })
      } catch (failure) { setError(errorMessage(failure)) }
    })} noValidate>
      <h2>Crear una cuenta</h2>
      {error && <p className="form-error" role="alert">{error}</p>}
      <label htmlFor="name">Nombre completo</label>
      <input id="name" autoComplete="name" {...register('name')} aria-invalid={!!errors.name} />
      {errors.name && <p className="field-error">{errors.name.message}</p>}
      <label htmlFor="email">Correo electrónico</label>
      <input id="email" type="email" autoComplete="email" {...register('email')} aria-invalid={!!errors.email} />
      {errors.email && <p className="field-error">{errors.email.message}</p>}
      <label htmlFor="password">Contraseña</label>
      <input id="password" type="password" autoComplete="new-password" {...register('password')} aria-invalid={!!errors.password} aria-describedby="password-hint" />
      <p id="password-hint" className="field-hint">Usa una frase de al menos 12 caracteres.</p>
      {errors.password && <p className="field-error">{errors.password.message}</p>}
      <label htmlFor="confirmPassword">Repite la contraseña</label>
      <input id="confirmPassword" type="password" autoComplete="new-password" {...register('confirmPassword')} aria-invalid={!!errors.confirmPassword} />
      {errors.confirmPassword && <p className="field-error">{errors.confirmPassword.message}</p>}
      <button type="submit" disabled={isSubmitting}>{isSubmitting ? 'Creando cuenta…' : 'Crear mi cuenta'}</button>
      <p className="form-footer">¿Ya tienes una cuenta? <Link to="/login">Inicia sesión</Link></p>
    </form>
  </section>
}
