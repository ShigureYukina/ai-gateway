import type { ConfigExportResponse, ConfigImportResponse } from '@/types/api'

import { request } from '../../http'

export const config = {
  export: () =>
    request<ConfigExportResponse>('/admin/config/export'),
  import_: (data: Record<string, unknown>, dryRun = false) =>
    request<ConfigImportResponse>(`/admin/config/import?dryRun=${dryRun}`, {
      method: 'POST',
      body: JSON.stringify(data),
    }),
}
