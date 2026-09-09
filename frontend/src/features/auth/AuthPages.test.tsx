import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LoginPage, RegisterPage } from './AuthPages'
import { login, register } from './api/auth'

vi.mock('./useAuth', () => ({ useAuth: () => ({ user: null, ready: true }) }))
vi.mock('./api/auth', () => ({
  login: vi.fn(), register: vi.fn(), errorMessage: (error: Error) => error.message,
}))

beforeEach(() => { vi.clearAllMocks() })

function pages(path: string) {
  render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/register" element={<RegisterPage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/account" element={<p>Mi perfil</p>} />
  </Routes></MemoryRouter>)
}

describe('identity forms', () => {
  it('validates login input before calling the backend', async () => {
    pages('/login')
    fireEvent.click(screen.getByRole('button', { name: 'Entrar a mi cuenta' }))
    expect(await screen.findByText('Introduce un correo válido.')).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  it('shows invalid credentials and allows correction', async () => {
    vi.mocked(login).mockRejectedValueOnce(new Error('Correo o contraseña incorrectos.')).mockResolvedValueOnce()
    pages('/login')
    fireEvent.change(screen.getByLabelText('Correo electrónico'), { target: { value: 'ana@example.test' } })
    fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'My test passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: 'Entrar a mi cuenta' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Correo o contraseña incorrectos.')
    fireEvent.click(screen.getByRole('button', { name: 'Entrar a mi cuenta' }))
    expect(await screen.findByText('Mi perfil')).toBeInTheDocument()
  })

  it('registers only public fields and directs the client to login', async () => {
    vi.mocked(register).mockResolvedValue({ id: 'id', name: 'Ana', email: 'ana@example.test', role: 'CLIENTE', createdAt: '' })
    pages('/register')
    fireEvent.change(screen.getByLabelText('Nombre completo'), { target: { value: 'Ana' } })
    fireEvent.change(screen.getByLabelText('Correo electrónico'), { target: { value: 'ana@example.test' } })
    fireEvent.change(screen.getByLabelText('Contraseña', { exact: true }), { target: { value: 'My test passphrase' } })
    fireEvent.change(screen.getByLabelText('Repite la contraseña'), { target: { value: 'My test passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: 'Crear mi cuenta' }))
    expect(await screen.findByText('Tu cuenta está creada. Ya puedes iniciar sesión.')).toBeInTheDocument()
    expect(register).toHaveBeenCalledWith({ name: 'Ana', email: 'ana@example.test', password: 'My test passphrase' })
  })
})
