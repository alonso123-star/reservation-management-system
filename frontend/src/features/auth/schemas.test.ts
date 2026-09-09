import { describe, expect, it } from 'vitest'
import { password, passwordSchema } from './schemas'

describe('password policy', () => {
  it('accepts long passphrases without arbitrary composition rules', () => {
    expect(password.safeParse('una frase larga y fácil de recordar').success).toBe(true)
  })
  it('rejects UTF-8 input that BCrypt would truncate', () => {
    expect(password.safeParse('é'.repeat(40)).success).toBe(false)
  })
  it('requires matching confirmation', () => {
    expect(passwordSchema.safeParse({ currentPassword: 'current phrase', newPassword: 'my new passphrase', confirmPassword: 'different phrase' }).success).toBe(false)
  })
})
