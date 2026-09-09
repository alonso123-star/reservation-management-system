import { useEffect, type ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { refreshSession } from './api/auth'
import { useAuth } from './useAuth'

export function AuthProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  useEffect(() => { void refreshSession().catch(() => undefined) }, [])
  useEffect(() => { if (!user) queryClient.clear() }, [user, queryClient])
  return children
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth()
  if (!ready) return <p className="account-loading" role="status">Recuperando tu sesión…</p>
  return user ? children : <Navigate to="/login" replace />
}
