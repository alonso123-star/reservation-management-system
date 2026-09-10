import { useState } from 'react'
import { Link, Route, Routes, useNavigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider, RequireAuth } from '../features/auth/AuthProvider'
import { useAuth } from '../features/auth/useAuth'
import { LoginPage, RegisterPage } from '../features/auth/AuthPages'
import { AccountPage } from '../features/auth/AccountPage'
import { errorMessage, logout } from '../features/auth/api/auth'
import Home from './Home'
import { CatalogPage, CatalogDetail } from '../features/rooms/CatalogPage'
import { RoleGuard } from '../features/rooms/RoleGuard'
import { AvailabilityPage } from '../features/rooms/AvailabilityPage'
import { ReservationHistory, ReservationDetail } from '../features/reservations/ReservationPages'

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30000 } } })

function Layout() {
  const { user, ready } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [leaving, setLeaving] = useState(false)
  return <div className="page">
    <header className="header">
      <Link className="brand" to="/" aria-label="Reservation Management System, inicio">
        <span className="brand-icon" aria-hidden="true">R</span>
        <span>Reservation<span className="brand-subtitle">Management System</span></span>
      </Link>
      <nav className="auth-nav" aria-label="Navegación principal">
        <Link to="/catalog/types">Catálogo</Link>
        <Link to="/availability">Buscar estancia</Link>
        {user && user.role !== 'CLIENTE' && <Link to="/staff/catalog/rooms">Inventario</Link>}
        {ready && (user ? <>
          <Link to="/account">Mi cuenta</Link>
          <Link to="/reservations">{user.role === 'CLIENTE' ? 'Mis reservas' : 'Reservas'}</Link>
          <button className="nav-button" disabled={leaving} onClick={async () => {
            setError(''); setLeaving(true)
            try { await logout(); navigate('/login', { replace: true }) }
            catch (failure) { setError(errorMessage(failure)) }
            finally { setLeaving(false) }
          }}>{leaving ? 'Saliendo…' : 'Salir'}</button>
        </> : <><Link to="/login">Iniciar sesión</Link><Link className="nav-cta" to="/register">Crear cuenta</Link></>)}
      </nav>
    </header>
    {error && <p className="form-error" role="alert">{error}</p>}
    <main>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/availability" element={<AvailabilityPage />} />
        <Route path="/reservations" element={<RequireAuth><ReservationHistory /></RequireAuth>} />
        <Route path="/reservations/:id" element={<RequireAuth><ReservationDetail /></RequireAuth>} />
        <Route path="/catalog/types" element={<CatalogPage kind="types" key="public-types" />} />
        <Route path="/catalog/rooms" element={<CatalogPage kind="rooms" key="public-rooms" />} />
        <Route path="/catalog/types/:id" element={<CatalogDetail kind="types" />} />
        <Route path="/catalog/rooms/:id" element={<CatalogDetail kind="rooms" />} />
        <Route path="/staff/catalog/types" element={<RoleGuard><CatalogPage kind="types" staff key="staff-types" /></RoleGuard>} />
        <Route path="/staff/catalog/rooms" element={<RoleGuard><CatalogPage kind="rooms" staff key="staff-rooms" /></RoleGuard>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/account" element={<RequireAuth><AccountPage /></RequireAuth>} />
        <Route path="*" element={<section className="account-page"><h1>Página no encontrada</h1><Link to="/">Volver al inicio</Link></section>} />
      </Routes>
    </main>
    <footer><span>Reservation Management System</span><span>Proyecto de portafolio · Catálogo del hotel</span></footer>
  </div>
}

export default function App() {
  return <QueryClientProvider client={queryClient}><AuthProvider><Layout /></AuthProvider></QueryClientProvider>
}
