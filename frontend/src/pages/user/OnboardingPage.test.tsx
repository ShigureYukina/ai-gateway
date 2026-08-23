import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import UserOnboardingPage from './OnboardingPage'

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
})

describe('UserOnboardingPage', () => {
  it('renders all sections', () => {
    renderWithProviders(<UserOnboardingPage />)

    expect(screen.getByText('Connection Guide')).toBeInTheDocument()
    expect(screen.getByText('Base URL')).toBeInTheDocument()
    expect(screen.getByText('http://localhost:8081')).toBeInTheDocument()
    expect(screen.getByText('Your API Key')).toBeInTheDocument()
    expect(screen.getByText('Create an API key from the "My API Keys" page.')).toBeInTheDocument()
    expect(screen.getByText('Available Models')).toBeInTheDocument()
    expect(screen.getByText('Use `GET /v1/models` to list available models, or check with your admin.')).toBeInTheDocument()
    expect(screen.getByText('Usage Example (OpenAI SDK)')).toBeInTheDocument()
    expect(screen.getByText('cURL Example')).toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(3)
  })

  it('copy buttons render with Copy icon', () => {
    renderWithProviders(<UserOnboardingPage />)

    const copyIcons = document.querySelectorAll('svg.lucide-copy')
    expect(copyIcons.length).toBe(3)
  })
})
