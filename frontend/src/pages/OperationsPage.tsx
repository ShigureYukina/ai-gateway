import { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { alerts, requestLogs, configAudit, dashboard } from '@/api/client'
import { useTranslation } from '@/i18n'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { RequestLogEntryView } from '@/types/api'
import { RequestDetailView } from '@/components/operations/RequestDetailView'
import { CostPanel } from '@/components/operations/CostPanel'

/* ─── helpers ─── */

const severityColor: Record<string, string> = {
  critical: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  warning: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400',
  info: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
}

const alertStatusColor: Record<string, string> = {
  open: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  acknowledged: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400',
  resolved: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
}

const PAGE_SIZE = 50

/* ================================================================== */

export default function OperationsPage() {
  const { t } = useTranslation()
  const [statusFilter, setStatusFilter] = useState('')
  const [limit, setLimit] = useState(String(PAGE_SIZE))
  const [logOffset, setLogOffset] = useState(0)
  const [auditOffset, setAuditOffset] = useState(0)
  const [selectedEntry, setSelectedEntry] = useState<RequestLogEntryView | null>(null)
  const op = t.operations

  /* ── Alerts ── */
  const { data: alertsData, isLoading: alertsLoading, refetch: refetchAlerts } = useQuery({
    queryKey: ['alerts'],
    queryFn: () => alerts.list(),
    refetchInterval: 30_000,
  })

  /* ── Request Logs ── */
  const logLimit = Math.min(Math.max(parseInt(limit) || PAGE_SIZE, 1), 500)
  const { data: logsData, isLoading: logsLoading, refetch: refetchLogs } = useQuery({
    queryKey: ['requestLogs', statusFilter, logLimit, logOffset],
    queryFn: () => requestLogs.recent({
      status: statusFilter ? parseInt(statusFilter) : undefined,
      limit: logLimit,
      offset: logOffset,
    }),
    refetchInterval: 15_000,
  })

  /* ── Audit ── */
  const { data: auditData, isLoading: auditLoading, refetch: refetchAudit } = useQuery({
    queryKey: ['configAudit', auditOffset],
    queryFn: () => configAudit.center({ limit: PAGE_SIZE, offset: auditOffset }),
    refetchInterval: 30_000,
  })

  /* ── Dashboard overview for cost panel ── */
  const { data: dashData, isLoading: dashLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => dashboard.overview(),
    staleTime: 60_000,
  })

  /* ── pagination ── */
  const logNext = useCallback(() => setLogOffset(o => o + logLimit), [logLimit])
  const logPrev = useCallback(() => setLogOffset(o => Math.max(0, o - logLimit)), [logLimit])
  const auditNext = useCallback(() => setAuditOffset(o => o + PAGE_SIZE), [])
  const auditPrev = useCallback(() => setAuditOffset(o => Math.max(0, o - PAGE_SIZE)), [])

  return (
    <div className="space-y-6">
      <Tabs defaultValue="alerts">
        <TabsList>
          <TabsTrigger value="alerts">{op?.alerts || 'Alerts'}</TabsTrigger>
          <TabsTrigger value="logs">{op?.requestLogs || 'Request Logs'}</TabsTrigger>
          <TabsTrigger value="audit">{op?.audit || 'Audit'}</TabsTrigger>
          <TabsTrigger value="cost">{op?.costPanel || 'Cost Overview'}</TabsTrigger>
        </TabsList>

        {/* ══ Alerts Tab ══ */}
        <TabsContent value="alerts" className="space-y-6 mt-6">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">
              {op?.activeAlerts || 'Active Alerts'}
              {alertsData && alertsData.active?.length != null && (
                <span className="ml-2 text-sm font-normal text-muted-foreground">({alertsData.active.length})</span>
              )}
            </h2>
            <Button variant="outline" size="sm" onClick={() => refetchAlerts()}>{t.common?.refresh || 'Refresh'}</Button>
          </div>
          {alertsLoading ? (
            <div className="text-center py-8 text-muted-foreground">{t.common?.loading || 'Loading...'}</div>
          ) : alertsData && alertsData.active.length > 0 ? (
            <div className="space-y-3">
              {alertsData.active.map(alert => (
                <Card key={alert.id} className="border-l-4 border-l-red-500">
                  <CardContent className="pt-4">
                    <div className="flex items-start justify-between">
                      <div className="space-y-1">
                        <div className="flex items-center gap-2">
                          <span className={`px-2 py-0.5 rounded text-xs font-medium ${severityColor[alert.severity] || severityColor.info}`}>{alert.severity}</span>
                          <span className={`px-2 py-0.5 rounded text-xs font-medium ${alertStatusColor[alert.status] || ''}`}>{alert.status}</span>
                          <span className="text-xs text-muted-foreground">{alert.type}</span>
                        </div>
                        <p className="text-sm">{alert.message}</p>
                        <p className="text-xs text-muted-foreground">{alert.source} · {new Date(alert.detectedAt).toLocaleString()}</p>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          ) : (
            <Card><CardContent className="py-8 text-center text-muted-foreground">{op?.noActiveAlerts || 'No active alerts'}</CardContent></Card>
          )}
          <h3 className="text-md font-semibold mt-8">{op?.recentAlerts || 'Recent Alerts'}</h3>
          {alertsData && alertsData.recent.length > 0 ? (
            <div className="space-y-2">
              {alertsData.recent.map(alert => (
                <Card key={alert.id}><CardContent className="py-3">
                  <div className="flex items-center gap-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${severityColor[alert.severity] || severityColor.info}`}>{alert.severity}</span>
                    <span className="text-sm flex-1">{alert.message}</span>
                    <span className="text-xs text-muted-foreground whitespace-nowrap">{new Date(alert.detectedAt).toLocaleString()}</span>
                  </div>
                </CardContent></Card>
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{op?.noRecentAlerts || 'No recent alerts'}</p>
          )}
        </TabsContent>

        {/* ══ Request Logs Tab ══ */}
        <TabsContent value="logs" className="space-y-4 mt-6">
          <div className="flex items-center gap-4 flex-wrap">
            <div className="flex items-center gap-2">
              <label className="text-sm text-muted-foreground">{op?.filterStatus || 'Status'}:</label>
              <Input type="number" placeholder={op?.statusPlaceholder || 'e.g. 200'} value={statusFilter} onChange={e => setStatusFilter(e.target.value)} className="w-24 h-8" />
            </div>
            <div className="flex items-center gap-2">
              <label className="text-sm text-muted-foreground">{op?.limit || 'Limit'}:</label>
              <Input type="number" value={limit} onChange={e => setLimit(e.target.value)} className="w-20 h-8" min={1} max={500} />
            </div>
            <Button variant="outline" size="sm" onClick={() => refetchLogs()}>{t.common?.refresh || 'Refresh'}</Button>
            {logsData && (
              <div className="flex items-center gap-2 ml-auto">
                <span className="text-xs text-muted-foreground whitespace-nowrap">{logOffset + 1}-{Math.min(logOffset + logLimit, logsData.total)} of {logsData.total}</span>
                <Button variant="outline" size="sm" disabled={logOffset === 0} onClick={logPrev}>{op?.prev || 'Prev'}</Button>
                <Button variant="outline" size="sm" disabled={logOffset + logLimit >= logsData.total} onClick={logNext}>{op?.next || 'Next'}</Button>
              </div>
            )}
          </div>
          {logsLoading ? (
            <div className="text-center py-8 text-muted-foreground">{t.common?.loading || 'Loading...'}</div>
          ) : logsData && logsData.requests.length > 0 ? (
            <div className="border rounded-lg overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="text-xs">Request ID</TableHead>
                    <TableHead className="text-xs">{op?.model || 'Model'}</TableHead>
                    <TableHead className="text-xs">{op?.client || 'Client'}</TableHead>
                    <TableHead className="text-xs">{op?.provider || 'Provider'}</TableHead>
                    <TableHead className="text-xs">{op?.route || 'Route'}</TableHead>
                    <TableHead className="text-xs text-right">{op?.status || 'Status'}</TableHead>
                    <TableHead className="text-xs text-right">{op?.latency || 'Latency'}</TableHead>
                    <TableHead className="text-xs text-right">{op?.tokens || 'Tokens'}</TableHead>
                    <TableHead className="text-xs text-right">{op?.cost || 'Cost'}</TableHead>
                    <TableHead className="text-xs">{op?.timestamp || 'Timestamp'}</TableHead>
                    <TableHead className="text-xs">{op?.error || 'Error'}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {logsData.requests.map(entry => (
                    <TableRow
                      key={entry.requestId}
                      className={`cursor-pointer ${entry.status >= 400 ? 'bg-red-50 dark:bg-red-950/20' : 'hover:bg-muted/50'}`}
                      onClick={() => setSelectedEntry(entry)}
                    >
                      <TableCell className="text-xs font-mono max-w-[100px] truncate" title={entry.requestId}>{entry.requestId.slice(0, 12)}...</TableCell>
                      <TableCell className="text-xs">{entry.model}</TableCell>
                      <TableCell className="text-xs">{entry.clientId}</TableCell>
                      <TableCell className="text-xs">{entry.provider || '-'}</TableCell>
                      <TableCell className="text-xs">{entry.routeId || '-'}</TableCell>
                      <TableCell className="text-xs text-right"><Badge variant={entry.status >= 400 ? 'destructive' : entry.status >= 300 ? 'warning' : 'default'} className="text-xs">{entry.status}</Badge></TableCell>
                      <TableCell className="text-xs text-right tabular-nums">{entry.latencyMs}ms</TableCell>
                      <TableCell className="text-xs text-right tabular-nums">{entry.usageTokens != null ? entry.usageTokens.toLocaleString() : '-'}</TableCell>
                      <TableCell className="text-xs text-right tabular-nums">{entry.costUsd != null ? `$${entry.costUsd.toFixed(6)}` : '-'}</TableCell>
                      <TableCell className="text-xs whitespace-nowrap">{entry.timestamp ? new Date(entry.timestamp).toLocaleString() : '-'}</TableCell>
                      <TableCell className="text-xs max-w-[150px] truncate text-red-600" title={entry.errorMessage || ''}>{entry.errorMessage || '-'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          ) : (
            <Card><CardContent className="py-8 text-center text-muted-foreground">{op?.noRequests || 'No request logs yet'}</CardContent></Card>
          )}
          <Dialog open={selectedEntry != null} onOpenChange={open => { if (!open) setSelectedEntry(null) }}>
            <DialogContent className="max-w-2xl">
              <DialogHeader><DialogTitle>{op?.detailTitle || 'Request Detail'}</DialogTitle></DialogHeader>
              {selectedEntry && <RequestDetailView entry={selectedEntry} />}
            </DialogContent>
          </Dialog>
        </TabsContent>

        {/* ══ Audit Tab ══ */}
        <TabsContent value="audit" className="space-y-4 mt-6">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">{op?.auditEvents || 'Audit Events'}</h2>
            <Button variant="outline" size="sm" onClick={() => refetchAudit()}>{t.common?.refresh || 'Refresh'}</Button>
          </div>
          {auditData && (
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">{auditOffset + 1}-{auditOffset + auditData.entries.length}</span>
              <Button variant="outline" size="sm" disabled={auditOffset === 0} onClick={auditPrev}>{op?.prev || 'Prev'}</Button>
              <Button variant="outline" size="sm" disabled={auditData.entries.length < PAGE_SIZE} onClick={auditNext}>{op?.next || 'Next'}</Button>
            </div>
          )}
          {auditLoading ? (
            <div className="text-center py-8 text-muted-foreground">{t.common?.loading || 'Loading...'}</div>
          ) : auditData && auditData.entries.length > 0 ? (
            <div className="border rounded-lg overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="text-xs">{op?.auditEventType || 'Event Type'}</TableHead>
                    <TableHead className="text-xs">{op?.timestamp || 'Timestamp'}</TableHead>
                    <TableHead className="text-xs">{op?.auditActor || 'Actor'}</TableHead>
                    <TableHead className="text-xs">{op?.auditResource || 'Resource'}</TableHead>
                    <TableHead className="text-xs">{op?.auditAction || 'Action'}</TableHead>
                    <TableHead className="text-xs">{op?.auditResult || 'Result'}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {auditData.entries.map((entry, i) => (
                    <TableRow key={`${entry.timestamp}-${i}`}>
                      <TableCell className="text-xs"><Badge variant="outline" className="text-xs">{entry.eventType}</Badge></TableCell>
                      <TableCell className="text-xs whitespace-nowrap">{entry.timestamp ? new Date(entry.timestamp).toLocaleString() : '-'}</TableCell>
                      <TableCell className="text-xs">{entry.actor || '-'}</TableCell>
                      <TableCell className="text-xs font-mono">{entry.resourceType}/{entry.resourceId}</TableCell>
                      <TableCell className="text-xs">{entry.action || '-'}</TableCell>
                      <TableCell className="text-xs"><Badge variant={entry.result === 'success' ? 'default' : 'destructive'} className="text-xs">{entry.result}</Badge></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          ) : (
            <Card><CardContent className="py-8 text-center text-muted-foreground">{op?.noAuditEvents || 'No audit events'}</CardContent></Card>
          )}
        </TabsContent>

        {/* ══ Cost Overview Tab ══ */}
        <TabsContent value="cost" className="space-y-6 mt-6">
          {dashLoading ? (
            <div className="text-center py-8 text-muted-foreground">{t.common?.loading || 'Loading...'}</div>
          ) : dashData ? (
            <CostPanel data={dashData} />
          ) : (
            <Card><CardContent className="py-8 text-center text-muted-foreground">{t.dashboard?.noData || 'No data'}</CardContent></Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
