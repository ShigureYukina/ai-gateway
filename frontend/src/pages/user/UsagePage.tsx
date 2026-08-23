import { useEffect, useState } from 'react'
import { auth } from '@/api/client'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { toast } from '@/components/ui/toast'
import { useTranslation } from '@/i18n'
import { LoadingScreen } from '@/components/ui/loading'
import type { UsageRequestEntry, ModelCostEntry } from '@/types/api'

export default function UserUsagePage() {
  const { t } = useTranslation()
  const [requests, setRequests] = useState<UsageRequestEntry[]>([])
  const [costs, setCosts] = useState<ModelCostEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState('requests')

  // Recent params
  const [limit, setLimit] = useState(50)

  // Cost date params
  const today = new Date().toISOString().slice(0, 10)
  const [costFrom, setCostFrom] = useState(today)
  const [costTo, setCostTo] = useState(today)

  const loadRequests = () => {
    auth.usageRecent({ limit })
      .then((res) => setRequests(res.requests))
      .catch(() => toast({ title: t.userUsage.loadingFailed, variant: 'error' }))
  }

  const loadCosts = () => {
    auth.usageCosts({ from: costFrom, to: costTo })
      .then((res) => setCosts(res.models))
      .catch(() => toast({ title: t.userUsage.loadingFailed, variant: 'error' }))
  }

  useEffect(() => {
    (async () => {
      setLoading(true)
      await Promise.all([
        auth.usageRecent({ limit: 50 }).then((r) => setRequests(r.requests)).catch(() => {}),
        auth.usageCosts({}).then((r) => setCosts(r.models)).catch(() => {}),
      ])
      setLoading(false)
    })()
  }, [])

  if (loading) return <LoadingScreen />

  const formatTime = (ts: string) => new Date(ts).toLocaleString()

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t.userUsage.title}</h1>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList>
          <TabsTrigger value="requests">{t.userUsage.tabRequests}</TabsTrigger>
          <TabsTrigger value="costs">{t.userUsage.tabCosts}</TabsTrigger>
        </TabsList>

        <TabsContent value="requests" className="space-y-4 mt-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="text-base">{t.userUsage.tabRequests}</CardTitle>
              <div className="flex items-center gap-2">
                <Label className="text-sm">{t.operations.limit}</Label>
                <Input
                  type="number"
                  className="w-20 h-8"
                  value={limit}
                  onChange={(e) => setLimit(Number(e.target.value))}
                />
                <Button size="sm" variant="outline" onClick={loadRequests}>{t.common.refresh}</Button>
              </div>
            </CardHeader>
            <CardContent>
              {requests.length === 0 ? (
                <p className="text-muted-foreground text-center py-8">{t.userUsage.noRequests}</p>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t.userUsage.model}</TableHead>
                      <TableHead>{t.userUsage.status}</TableHead>
                      <TableHead>{t.userUsage.latency}</TableHead>
                      <TableHead>{t.userUsage.tokens}</TableHead>
                      <TableHead>{t.userUsage.cost}</TableHead>
                      <TableHead>{t.userUsage.timestamp}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {requests.map((r) => (
                      <TableRow key={r.requestId}>
                        <TableCell className="font-medium">{r.model}</TableCell>
                        <TableCell>
                          <Badge variant={r.status < 400 ? 'success' : r.status < 500 ? 'warning' : 'destructive'}>
                            {r.status}
                          </Badge>
                        </TableCell>
                        <TableCell>{r.latencyMs}ms</TableCell>
                        <TableCell>{r.usageTokens ?? '-'}</TableCell>
                        <TableCell>${r.costUsd?.toFixed(6) ?? '-'}</TableCell>
                        <TableCell className="text-xs">{formatTime(r.timestamp)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="costs" className="space-y-4 mt-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-base">{t.userUsage.tabCosts}</CardTitle>
                <div className="flex items-center gap-3">
                  <div className="flex items-center gap-1">
                    <Label className="text-xs">{t.userUsage.from}</Label>
                    <Input type="date" className="w-36 h-8" value={costFrom} onChange={(e) => setCostFrom(e.target.value)} />
                  </div>
                  <div className="flex items-center gap-1">
                    <Label className="text-xs">{t.userUsage.to}</Label>
                    <Input type="date" className="w-36 h-8" value={costTo} onChange={(e) => setCostTo(e.target.value)} />
                  </div>
                  <Button size="sm" variant="outline" onClick={loadCosts}>{t.common.refresh}</Button>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {costs.length === 0 ? (
                <p className="text-muted-foreground text-center py-8">{t.common.noData}</p>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t.userUsage.modelCost}</TableHead>
                      <TableHead>{t.userUsage.requests}</TableHead>
                      <TableHead>{t.userUsage.totalTokens}</TableHead>
                      <TableHead>{t.userUsage.totalCost}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {costs.map((m) => (
                      <TableRow key={m.model}>
                        <TableCell className="font-medium">{m.model}</TableCell>
                        <TableCell>{m.requests}</TableCell>
                        <TableCell>{m.totalTokens}</TableCell>
                        <TableCell>${m.totalCostUsd.toFixed(6)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
