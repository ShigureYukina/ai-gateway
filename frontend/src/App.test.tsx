/// <reference types="vitest" />
import { render, screen } from '@testing-library/react'
import App from './App'

describe('App smoke test', () => {
  it('renders login page when not authenticated', () => {
    render(<App />)

    // 未认证时跳转到登录页，应显示登录标题
    expect(screen.getByText('AI Gateway')).toBeInTheDocument()
    expect(screen.getByText('Sign in to admin console')).toBeInTheDocument()
  })

  it('renders username and password fields', () => {
    render(<App />)

    expect(screen.getByLabelText('Username')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })
})
