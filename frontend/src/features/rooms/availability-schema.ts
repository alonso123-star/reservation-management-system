import { z } from 'zod'

const optionalPrice = z.string().refine(value => value === '' || /^\d{1,10}(\.\d{1,2})?$/.test(value),
  'Usa un precio no negativo con hasta dos decimales.')
export const availabilitySchema = z.object({
  checkIn: z.iso.date('Introduce una fecha de entrada válida.'),
  checkOut: z.iso.date('Introduce una fecha de salida válida.'),
  guests: z.string().regex(/^[1-9]\d*$/, 'Indica un número entero positivo de huéspedes.')
    .refine(value => Number(value) <= 2147483647, 'Número de huéspedes demasiado grande.'),
  roomTypeId: z.union([z.literal(''), z.uuid()]),
  minPrice: optionalPrice,
  maxPrice: optionalPrice,
  sort: z.enum(['code,asc', 'basePrice,asc', 'basePrice,desc', 'capacity,asc', 'capacity,desc']),
  size: z.enum(['6', '12', '24']),
}).superRefine((value, context) => {
  if (value.checkIn && value.checkOut && value.checkOut <= value.checkIn)
    context.addIssue({ code: 'custom', path: ['checkOut'], message: 'La salida debe ser posterior a la entrada.' })
  if (value.minPrice !== '' && value.maxPrice !== '' && Number(value.minPrice) > Number(value.maxPrice))
    context.addIssue({ code: 'custom', path: ['maxPrice'], message: 'El precio máximo debe ser igual o mayor que el mínimo.' })
})
export type AvailabilityForm = z.infer<typeof availabilitySchema>
