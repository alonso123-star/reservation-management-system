import { money, statusLabels, type Reservation } from './api'

// Ellipsis is CSS-only: the DOM, accessible text and all resource links keep the full identifier.
export function ReservationIdentifier({ value, compact = true }: { value: string; compact?: boolean }) {
  return <span className={compact ? 'reservation-identifier is-compact' : 'reservation-identifier'} title={value}>{value}</span>
}

export function ReservationStatus({ status }: { status: Reservation['status'] }) {
  return <p className={'reservation-badge reservation-badge--' + status.toLowerCase()}>Estado: {statusLabels[status]}</p>
}

export function ReservationSummary({ value, compact = true }: { value: Reservation; compact?: boolean }) {
  return <div className="reservation-summary">
    <div className="reservation-heading">
      <h2 className="reservation-code"><ReservationIdentifier value={value.code} compact={compact} /></h2>
      <ReservationStatus status={value.status} />
    </div>
    <p className="reservation-room">Habitación {value.roomCode} · {value.roomTypeName}</p>
    <p className="reservation-meta">{value.checkIn} al {value.checkOut} · {value.nights} noches · {value.guests} huéspedes</p>
    <p className="reservation-price">{money(value.agreedNightlyRate, value.currency)} por noche · <strong>Total: {money(value.totalAmount, value.currency)}</strong> · {value.currency}</p>
    {value.cancellationReason && <p className="reservation-meta">Motivo: {value.cancellationReason}</p>}
  </div>
}
