import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import App from './Home'

describe('foundation connectivity', () => {
  it('shows readiness after the health contract reports UP', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    render(<MemoryRouter><App /></MemoryRouter>)
    expect(screen.getByRole('status')).toHaveTextContent('Comprobando conexión')
    expect(await screen.findByText('Servicios disponibles')).toBeInTheDocument()
  })

  it('reports failure and lets the user retry successfully', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('{"status":"DOWN"}', { status: 503 }))
      .mockResolvedValueOnce(new Response('{"status":"UP"}'))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter><App /></MemoryRouter>)
    expect(await screen.findByText('No se pudo conectar')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Volver a comprobar/ }))
    expect(await screen.findByText('Servicios disponibles')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not treat an unexpected successful response as a ready backend', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"hello"}')))
    render(<MemoryRouter><App /></MemoryRouter>)
    expect(await screen.findByText('No se pudo conectar')).toBeInTheDocument()
  })
})
