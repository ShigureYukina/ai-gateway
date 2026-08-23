import type { PublicationResponse, PublishModelRequest } from '@/types/api'

import { request } from '../../http'

export const publications = {
  publish: (alias: string, data: PublishModelRequest) =>
    request<PublicationResponse>(`/admin/publications/${alias}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
}
