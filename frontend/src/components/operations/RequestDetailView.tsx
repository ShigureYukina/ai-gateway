import { useTranslation } from '@/i18n'
import type { RequestLogEntryView } from '@/types/api'

export function RequestDetailView({ entry }: { entry: RequestLogEntryView }) {
  const { t } = useTranslation()
  const op = t.operations
  const rows: [string, string | number | null | undefined][] = [
    [op?.requestId || 'Request ID', entry.requestId],
    [op?.model || 'Model', entry.model],
    [op?.client || 'Client', entry.clientId],
    [op?.provider || 'Provider', entry.provider],
    [op?.route || 'Route', entry.routeId],
    [op?.scene || 'Scene', entry.scene],
    [op?.status || 'Status', entry.status],
    [op?.latency || 'Latency', entry.latencyMs != null ? `${entry.latencyMs}ms` : null],
    [op?.streamMode || 'Stream Mode', entry.streamMode],
    [op?.tokens || 'Tokens', entry.usageTokens?.toLocaleString() ?? null],
    [op?.promptTokens || 'Prompt Tokens', entry.promptTokens?.toLocaleString() ?? null],
    [op?.completionTokens || 'Completion Tokens', entry.completionTokens?.toLocaleString() ?? null],
    [op?.cost || 'Cost', entry.costUsd != null ? `$${entry.costUsd.toFixed(6)}` : null],
    [op?.timestamp || 'Timestamp', entry.timestamp ? new Date(entry.timestamp).toLocaleString() : null],
    [op?.error || 'Error', entry.errorMessage],
  ]
  return (
    <div className="space-y-3 max-h-[60vh] overflow-y-auto">
      <table className="w-full text-sm">
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label} className="border-b last:border-0">
              <td className="py-2 pr-4 text-muted-foreground whitespace-nowrap align-top w-36">{label}</td>
              <td className="py-2 break-all font-mono text-xs">{value ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
