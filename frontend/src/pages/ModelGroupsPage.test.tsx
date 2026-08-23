import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import ModelGroupsPage from './ModelGroupsPage'

const { mockList, mockUpsert, mockRemove } = vi.hoisted(() => ({
  mockList: vi.fn(),
  mockUpsert: vi.fn(),
  mockRemove: vi.fn(),
}))

vi.mock('@/api/modules/admin/model-groups', () => ({
  modelGroups: {
    list: (...args: unknown[]) => mockList(...args),
    upsert: (...args: unknown[]) => mockUpsert(...args),
    remove: (...args: unknown[]) => mockRemove(...args),
  },
}))

function createListResponse() {
  return {
    generatedAt: '2026-06-06T00:00:00Z',
    groups: {
      'chat-default': {
        alias: 'chat-default',
        scene: 'chat-default-scene',
        members: [
          {
            routeId: 'chat-default-primary',
            provider: 'openai-main',
            upstreamModel: 'gpt-4o-mini',
            weight: 1,
          },
          {
            routeId: 'chat-default-fallback-0',
            provider: 'azure-main',
            upstreamModel: 'gpt-4o-mini',
            weight: 2,
          },
        ],
        fallbackOrder: ['chat-default-fallback-0'],
        capabilities: {},
        pricing: {},
      },
    },
  }
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockList.mockReset()
  mockUpsert.mockReset()
  mockRemove.mockReset()
})

describe('ModelGroupsPage', () => {
  it('renders model groups list', async () => {
    mockList.mockResolvedValue(createListResponse())

    renderWithProviders(<ModelGroupsPage />)

    await waitFor(() => {
      expect(screen.getByText('chat-default')).toBeInTheDocument()
      expect(screen.getByText('chat-default-scene')).toBeInTheDocument()
      expect(screen.getByText('2 member(s)')).toBeInTheDocument()
      expect(screen.getByText('openai-main / gpt-4o-mini / w=1')).toBeInTheDocument()
    })
  })

  it('saves a new model group', async () => {
    const user = userEvent.setup()
    mockList
      .mockResolvedValueOnce({ generatedAt: '2026-06-06T00:00:00Z', groups: {} })
      .mockResolvedValueOnce(createListResponse())
    mockUpsert.mockResolvedValue({ members: [] })

    renderWithProviders(<ModelGroupsPage />)

    await screen.findByText('Add Model Group')
    await user.click(screen.getByRole('button', { name: /add model group/i }))
    await user.type(screen.getByRole('textbox', { name: 'Alias' }), 'chat-default')
    await user.type(screen.getByRole('textbox', { name: 'Provider' }), 'openai-main')
    await user.type(screen.getByRole('textbox', { name: 'Upstream Model' }), 'gpt-4o-mini')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpsert).toHaveBeenCalledWith('chat-default', {
        members: [
          {
            provider: 'openai-main',
            upstreamModel: 'gpt-4o-mini',
            weight: 1,
          },
        ],
      })
      expect(mockList).toHaveBeenCalledTimes(2)
    })
  })

  it('deletes a model group after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    mockList
      .mockResolvedValueOnce(createListResponse())
      .mockResolvedValueOnce({ generatedAt: '2026-06-06T00:00:00Z', groups: {} })
    mockRemove.mockResolvedValue(undefined)

    renderWithProviders(<ModelGroupsPage />)

    await screen.findByText('chat-default')
    await user.click(screen.getByTitle('Delete'))

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalled()
      expect(mockRemove).toHaveBeenCalledWith('chat-default')
      expect(mockList).toHaveBeenCalledTimes(2)
    })

    confirmSpy.mockRestore()
  })
})
