/**
 * admin 子域共享工具。
 */

export function buildQuery(params: Record<string, string | number | undefined>) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      qs.set(key, String(value))
    }
  })
  const query = qs.toString()
  return query ? `?${query}` : ''
}
