import { useEffect, useState } from 'react'
import { checkReadiness } from '../shared/api/health'

type Status = 'checking' | 'ready' | 'unavailable'

export default function App() {
  const [status, setStatus] = useState<Status>('checking')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    checkReadiness(controller.signal)
      .then(() => { if (!controller.signal.aborted) setStatus('ready') })
      .catch(() => { if (!controller.signal.aborted) setStatus('unavailable') })
    return () => controller.abort()
  }, [attempt])

  const statusText = {
    checking: 'Comprobando conexión…',
    ready: 'Servicios disponibles',
    unavailable: 'No se pudo conectar',
  }[status]

  return (
    <div className="page">
      <header className="header">
        <a className="brand" href="/" aria-label="Reservation Management System, inicio">
          <span className="brand-icon" aria-hidden="true">R</span>
          <span>Reservation<span className="brand-subtitle">Management System</span></span>
        </a>
        <span className="phase">Fase 01 · Base ejecutable</span>
      </header>
      <main>
        <section className="hero" aria-labelledby="title">
          <div className="hero-copy">
            <p className="eyebrow">GESTIÓN HOTELERA</p>
            <h1 id="title">Una buena estancia<br />empieza con orden.</h1>
            <p className="intro">Un espacio para conectar habitaciones, reservas y atención al huésped.</p>
            <div className="notice">
              <span className="notice-line" aria-hidden="true" />
              <p>La base del proyecto está preparada. Las funciones de reservas y acceso se incorporarán en las siguientes fases.</p>
            </div>
          </div>
          <div className="hotel-art" aria-hidden="true">
            <div className="sun" />
            <div className="building">
              <div className="roof">RESERVATION</div>
              <div className="windows">{Array.from({ length: 9 }, (_, i) => <span key={i} />)}</div>
              <div className="entrance" />
            </div>
            <div className="ground" />
            <span className="art-caption">El comienzo de una mejor gestión.</span>
          </div>
        </section>
        <section className="connection" aria-labelledby="connection-title">
          <div>
            <p className="eyebrow">ESTADO DEL ENTORNO</p>
            <h2 id="connection-title">Todo empieza con una conexión.</h2>
            <p>Comprueba que la aplicación y la base de datos estén listas.</p>
          </div>
          <div className="connection-actions">
            <p role="status" className={`status ${status}`}><span aria-hidden="true" />{statusText}</p>
            <button type="button" disabled={status === 'checking'} onClick={() => {
              setStatus('checking')
              setAttempt(value => value + 1)
            }}>Volver a comprobar <span aria-hidden="true">↗</span></button>
          </div>
        </section>
      </main>
      <footer><span>Reservation Management System</span><span>Proyecto de portafolio · Versión 0.1</span></footer>
    </div>
  )
}
