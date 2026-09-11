import type { ReactNode } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function RoleGuard({ children, message = 'No tienes permiso para administrar el catálogo.' }: { children: ReactNode; message?: string }) {
  const { user, ready } = useAuth()
  if (!ready) return <p role="status">Recuperando tu sesión…</p>
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN' && user.role !== 'EMPLEADO') return <section className="catalog-page">
    <h1>Acceso restringido</h1><p role="alert">{message}</p><Link to="/catalog/types">Ver catálogo público</Link>
  </section>
  return children
}
