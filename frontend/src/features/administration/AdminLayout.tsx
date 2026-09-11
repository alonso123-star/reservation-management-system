import type { ReactNode } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function AdminLayout({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth()
  if (!ready) return <p role="status">Recuperando tu sesión…</p>
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN') return <section className="catalog-page"><h1>Acceso restringido</h1><p role="alert">Solo ADMIN puede acceder a administración.</p></section>
  return <section className="catalog-page"><h1>Administración</h1><nav className="catalog-pagination" aria-label="Administración">
    <Link to="/admin/users">Usuarios</Link><Link to="/admin/dashboard">Panel</Link><Link to="/admin/audit-events">Auditoría</Link>
  </nav>{children}</section>
}
export function Pagination({ page, totalPages, busy, onPage }: { page: number; totalPages: number; busy: boolean; onPage: (page: number) => void }) {
  return <nav className="catalog-pagination" aria-label="Paginación">
    <button disabled={page === 0 || busy} onClick={() => onPage(page - 1)}>Anterior</button>
    <span>Página {page + 1} de {Math.max(1, totalPages)}</span>
    <button disabled={page + 1 >= totalPages || busy} onClick={() => onPage(page + 1)}>Siguiente</button>
  </nav>
}
