import { useEffect, useState, useCallback } from 'react'
import { useTranslation } from '@/i18n'
import { routes } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { LoadingScreen } from '@/components/ui/loading'
import { toast } from '@/components/ui/toast'
import { Plus, Pencil, Trash2 } from 'lucide-react'
import type { RouteConfig } from '@/types/api'
import { RouteEditDialog } from '@/components/routes/RouteEditDialog'

export default function RoutesPage() {
  const { t } = useTranslation()
  const [allRoutes, setAllRoutes] = useState<Record<string, RouteConfig>>({})
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingRoute, setEditingRoute] = useState<{ id: string; config: RouteConfig } | null>(null)

  const loadData = useCallback(async () => {
    setLoading(true)
    try {
      const routesRes = await routes.list()
      setAllRoutes(routesRes.routes)
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    let active = true
    void (async () => {
      setLoading(true)
      try {
        const routesRes = await routes.list()
        if (active) {
          setAllRoutes(routesRes.routes)
        }
      } catch (error: unknown) {
        if (active) {
          toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
        }
      } finally {
        if (active) {
          setLoading(false)
        }
      }
    })()

    return () => {
      active = false
    }
  }, [t])

  const openCreate = () => {
    setEditingRoute(null)
    setDialogOpen(true)
  }

  const openEdit = (id: string, config: RouteConfig) => {
    setEditingRoute({ id, config })
    setDialogOpen(true)
  }

  const handleDelete = async (id: string) => {
    if (!confirm(t.common.deleteConfirm.replace('{name}', id))) return
    try {
      await routes.remove(id)
      toast({ title: t.routes.deletedSuccess.replace('{name}', id), variant: 'success' })
      loadData()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    }
  }

  if (loading) return <LoadingScreen />

  const entries = Object.entries(allRoutes)

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.routes.title}</h1>
          <p className="text-sm text-muted-foreground">{t.routes.intro}</p>
          <p className="text-sm text-muted-foreground">{t.routes.count.replace('{count}', String(entries.length))}</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {t.routes.add}
        </Button>
      </div>

      {entries.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">{t.routes.noData}</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.routes.routeId}</TableHead>
                  <TableHead>{t.routes.provider}</TableHead>
                  <TableHead>{t.routes.upstreamModel}</TableHead>
                  <TableHead>{t.routes.strategy}</TableHead>
                  <TableHead>{t.routes.scene}</TableHead>
                  <TableHead>{t.routes.weight}</TableHead>
                  <TableHead>{t.common.status}</TableHead>
                  <TableHead className="text-right">{t.common.actions}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([id, config]) => (
                  <TableRow key={id}>
                    <TableCell className="font-medium">{id}</TableCell>
                    <TableCell>{config.provider}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {(config.upstreamModels && config.upstreamModels.length > 0
                          ? config.upstreamModels
                          : config.upstreamModel
                            ? [config.upstreamModel]
                            : []
                        ).map((m) => (
                          <Badge key={m} variant="secondary">{m}</Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{config.strategy || 'round-robin'}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{config.scene || '—'}</TableCell>
                    <TableCell>{config.weight || 1}</TableCell>
                    <TableCell>
                      <Badge variant={config.enabled ? 'success' : 'secondary'}>
                        {config.enabled ? t.common.enabled : t.common.disabled}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => openEdit(id, config)} title={t.common.edit}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleDelete(id)} title={t.common.delete}>
                          <Trash2 className="h-4 w-4 text-destructive" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <RouteEditDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        editingRoute={editingRoute}
        onSaved={loadData}
      />
    </div>
  )
}
