export async function checkReadiness(signal?: AbortSignal): Promise<void> {
  const response = await fetch('/api/v1/system/health/readiness', {
    signal: signal
      ? AbortSignal.any([signal, AbortSignal.timeout(8000)])
      : AbortSignal.timeout(8000),
    cache: 'no-store',
  })
  if (!response.ok) throw new Error('El servicio no está disponible.')
  const body: unknown = await response.json()
  if (!body || typeof body !== 'object' || !('status' in body) || body.status !== 'UP') {
    throw new Error('El servicio todavía no está listo.')
  }
}
