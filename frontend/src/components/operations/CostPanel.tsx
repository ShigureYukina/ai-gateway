import { useTranslation } from '@/i18n'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import type { DashboardOverview } from '@/types/api'

export function CostPanel({ data }: { data: DashboardOverview }) {
  const { t } = useTranslation()
  const overview = data.overview
  const avgCost = overview.totalRequests > 0 ? overview.totalCost / overview.totalRequests : 0
  return (
    <>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Card><CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">{t.operations?.totalCost || 'Total Cost'}</CardTitle></CardHeader><CardContent><p className="text-2xl font-bold">${overview.totalCost.toFixed(4)}</p></CardContent></Card>
        <Card><CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">{t.operations?.avgCost || 'Avg Cost / Request'}</CardTitle></CardHeader><CardContent><p className="text-2xl font-bold">${avgCost.toFixed(8)}</p></CardContent></Card>
        <Card><CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">{t.dashboard?.totalRequests || 'Requests'}</CardTitle></CardHeader><CardContent><p className="text-2xl font-bold">{overview.totalRequests.toLocaleString()}</p></CardContent></Card>
      </div>
      <Card>
        <CardHeader><CardTitle className="text-base">{t.operations?.topModelsCost || 'Top Models by Cost'}</CardTitle></CardHeader>
        <CardContent>
          {overview.topModels.length > 0 ? (
            <Table>
              <TableHeader><TableRow>
                <TableHead className="text-xs">{t.dashboard?.model || 'Model'}</TableHead>
                <TableHead className="text-xs text-right">{t.dashboard?.requests || 'Requests'}</TableHead>
                <TableHead className="text-xs text-right">{t.dashboard?.tokens || 'Tokens'}</TableHead>
                <TableHead className="text-xs text-right">{t.dashboard?.cost || 'Cost'}</TableHead>
              </TableRow></TableHeader>
              <TableBody>{overview.topModels.map(m => (
                <TableRow key={m.model}>
                  <TableCell className="text-xs">{m.model}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">{m.requests.toLocaleString()}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">{m.tokens.toLocaleString()}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">${m.cost.toFixed(4)}</TableCell>
                </TableRow>
              ))}</TableBody>
            </Table>
          ) : (<p className="text-sm text-muted-foreground">{t.dashboard?.noData || 'No data'}</p>)}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{t.operations?.topClientsCost || 'Top Clients by Cost'}</CardTitle></CardHeader>
        <CardContent>
          {overview.topClients.length > 0 ? (
            <Table>
              <TableHeader><TableRow>
                <TableHead className="text-xs">{t.dashboard?.client || 'Client'}</TableHead>
                <TableHead className="text-xs text-right">{t.dashboard?.requests || 'Requests'}</TableHead>
                <TableHead className="text-xs text-right">{t.dashboard?.tokens || 'Tokens'}</TableHead>
                <TableHead className="text-xs text-right">{t.dashboard?.cost || 'Cost'}</TableHead>
              </TableRow></TableHeader>
              <TableBody>{overview.topClients.map(c => (
                <TableRow key={c.client}>
                  <TableCell className="text-xs">{c.client}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">{c.requests.toLocaleString()}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">{c.tokens.toLocaleString()}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">${c.cost.toFixed(4)}</TableCell>
                </TableRow>
              ))}</TableBody>
            </Table>
          ) : (<p className="text-sm text-muted-foreground">{t.dashboard?.noData || 'No data'}</p>)}
        </CardContent>
      </Card>
    </>
  )
}
