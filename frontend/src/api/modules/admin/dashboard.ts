import type { DashboardOverview } from '@/types/api'

import { request } from '../../http'

export const dashboard = {
  overview: () =>
    request<DashboardOverview>('/admin/dashboard/overview'),
}
