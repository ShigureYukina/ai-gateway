import { useEffect, useState } from 'react'
import { useTranslation } from '@/i18n'
import { dashboard } from '@/api/client'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { LoadingScreen } from '@/components/ui/loading'
import { Separator } from '@/components/ui/separator'
import type { DashboardOverview } from '@/types/api'

export default function DashboardPage() {
  const [data, setData] = useState<DashboardOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { t } = useTranslation()

  useEffect(() => {
    dashboard.overview()
      .then(setData)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <LoadingScreen />
  if (error) return <div className="text-destructive">{t.dashboard.failedToLoad}: {error}</div>
  if (!data) return null

  const { overview, systemStatus, tpmOverview } = data

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t.dashboard.title}</h1>
        <p className="text-sm text-muted-foreground">
          {t.dashboard.overview.replace('{day}', data.day)}
        </p>
      </div>

      <div className="flex gap-2">
        <Badge variant={systemStatus.maintenanceActive ? 'destructive' : 'success'}>
          {systemStatus.maintenanceActive ? t.dashboard.maintActive : t.dashboard.active}
        </Badge>
        <Badge variant={systemStatus.hasAvailableRoute ? 'success' : 'warning'}>
          {systemStatus.hasAvailableRoute ? t.dashboard.routesAvailable : t.dashboard.noRoutes}
        </Badge>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard title={t.dashboard.totalRequests} value={overview.totalRequests.toLocaleString()} />
        <StatCard title={t.dashboard.totalTokens} value={overview.totalTokens.toLocaleString()} />
        <StatCard title={t.dashboard.totalCost} value={`$${overview.totalCost.toFixed(4)}`} />
        <StatCard
          title={t.dashboard.successRate}
          value={`${overview.successRate.toFixed(1)}%`}
          color={overview.successRate > 95 ? 'text-emerald-600' : 'text-amber-600'}
        />
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard title={t.dashboard.responses2xx} value={overview.success2xx.toLocaleString()} />
        <StatCard title={t.dashboard.errors4xx} value={overview.status4xx.toLocaleString()} color="text-amber-600" />
        <StatCard title={t.dashboard.errors5xx} value={overview.status5xx.toLocaleString()} color="text-destructive" />
        <StatCard title={t.dashboard.activeClients} value={overview.activeClients.toString()} />
      </div>

      <Separator />

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">{t.dashboard.topModels}</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.dashboard.model}</TableHead>
                  <TableHead className="text-right">{t.dashboard.requests}</TableHead>
                  <TableHead className="text-right">{t.dashboard.tokens}</TableHead>
                  <TableHead className="text-right">{t.dashboard.cost}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {overview.topModels.length === 0 ? (
                  <TableRow><TableCell colSpan={4} className="text-muted-foreground text-center">{t.dashboard.noData}</TableCell></TableRow>
                ) : (
                  overview.topModels.map((m) => (
                    <TableRow key={m.model}>
                      <TableCell className="font-medium">{m.model}</TableCell>
                      <TableCell className="text-right">{m.requests.toLocaleString()}</TableCell>
                      <TableCell className="text-right">{m.tokens.toLocaleString()}</TableCell>
                      <TableCell className="text-right">${m.cost.toFixed(4)}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">{t.dashboard.topClients}</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.dashboard.client}</TableHead>
                  <TableHead className="text-right">{t.dashboard.requests}</TableHead>
                  <TableHead className="text-right">{t.dashboard.tokens}</TableHead>
                  <TableHead className="text-right">{t.dashboard.cost}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {overview.topClients.length === 0 ? (
                  <TableRow><TableCell colSpan={4} className="text-muted-foreground text-center">{t.dashboard.noData}</TableCell></TableRow>
                ) : (
                  overview.topClients.map((c) => (
                    <TableRow key={c.client}>
                      <TableCell className="font-medium max-w-[150px] truncate">{c.client}</TableCell>
                      <TableCell className="text-right">{c.requests.toLocaleString()}</TableCell>
                      <TableCell className="text-right">{c.tokens.toLocaleString()}</TableCell>
                      <TableCell className="text-right">${c.cost.toFixed(4)}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </div>

      {tpmOverview.clients.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">{t.dashboard.tokensPerMinute}</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.dashboard.client}</TableHead>
                  <TableHead className="text-right">{t.dashboard.used}</TableHead>
                  <TableHead className="text-right">{t.dashboard.limit}</TableHead>
                  <TableHead className="text-right">{t.dashboard.utilization}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tpmOverview.clients.map((c) => (
                  <TableRow key={c.client}>
                    <TableCell className="font-medium max-w-[200px] truncate">{c.client}</TableCell>
                    <TableCell className="text-right">{c.usedTokens.toLocaleString()}</TableCell>
                    <TableCell className="text-right">{c.limitTokens.toLocaleString()}</TableCell>
                    <TableCell className="text-right">
                      <span className={c.utilizationPercent > 80 ? 'text-destructive' : c.utilizationPercent > 50 ? 'text-amber-600' : ''}>
                        {c.utilizationPercent.toFixed(1)}%
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

function StatCard({ title, value, color }: { title: string; value: string; color?: string }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className={`text-2xl font-bold ${color || ''}`}>{value}</p>
      </CardContent>
    </Card>
  )
}
