import { useCallback, useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { LoadingScreen } from '@/components/ui/loading'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { toast } from '@/components/ui/toast'
import { useTranslation } from '@/i18n'
import type { ModelGroupPutMember, ModelGroupPutRequest, ModelGroupView } from '@/types/api'
import { Plus, Pencil, Trash2, X } from 'lucide-react'

import { modelGroups } from '@/api/modules/admin/model-groups'

const TEXT = {
  title: 'Model Groups',
  intro: 'Manage published model aliases and their backing route members.',
  count: (count: number) => `${count} group(s) configured`,
  add: 'Add Model Group',
  edit: 'Edit Model Group',
  dialogDesc: 'A model group binds one public alias to one or more ordered upstream route members.',
  alias: 'Alias',
  aliasPlaceholder: 'gpt-4o-mini',
  members: 'Members',
  noData: 'No model groups configured yet.',
  routeCount: (count: number) => `${count} member(s)`,
  memberProvider: 'Provider',
  memberModel: 'Upstream Model',
  memberWeight: 'Weight',
  addMember: 'Add Member',
  saveSuccess: (alias: string) => `Saved model group ${alias}`,
  deleteSuccess: (alias: string) => `Deleted model group ${alias}`,
}

type EditableMember = Pick<ModelGroupPutMember, 'provider' | 'upstreamModel' | 'weight'>

const EMPTY_MEMBER: EditableMember = { provider: '', upstreamModel: '', weight: 1 }

export default function ModelGroupsPage() {
  const { t } = useTranslation()
  const [groups, setGroups] = useState<Record<string, ModelGroupView>>({})
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingAlias, setEditingAlias] = useState<string | null>(null)
  const [formAlias, setFormAlias] = useState('')
  const [formMembers, setFormMembers] = useState<EditableMember[]>([EMPTY_MEMBER])
  const [saving, setSaving] = useState(false)

  const loadGroups = useCallback(async () => {
    setLoading(true)
    try {
      const response = await modelGroups.list()
      setGroups(response.groups)
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    let active = true

    const initialLoad = async () => {
      try {
        const response = await modelGroups.list()
        if (active) {
          setGroups(response.groups)
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
    }

    void initialLoad()
    return () => {
      active = false
    }
  }, [t])

  const openCreate = () => {
    setEditingAlias(null)
    setFormAlias('')
    setFormMembers([{ ...EMPTY_MEMBER }])
    setDialogOpen(true)
  }

  const openEdit = (alias: string, group: ModelGroupView) => {
    setEditingAlias(alias)
    setFormAlias(alias)
    setFormMembers(
      group.members.length > 0
        ? group.members.map((member) => ({
            provider: member.provider,
            upstreamModel: member.upstreamModel,
            weight: member.weight,
          }))
        : [{ ...EMPTY_MEMBER }],
    )
    setDialogOpen(true)
  }

  const updateMember = (index: number, patch: Partial<EditableMember>) => {
    setFormMembers((prev) => prev.map((member, currentIndex) => (
      currentIndex === index ? { ...member, ...patch } : member
    )))
  }

  const addMember = () => {
    setFormMembers((prev) => [...prev, { ...EMPTY_MEMBER }])
  }

  const removeMember = (index: number) => {
    setFormMembers((prev) => (prev.length === 1 ? prev : prev.filter((_, currentIndex) => currentIndex !== index)))
  }

  const sanitizePayload = (): ModelGroupPutRequest | null => {
    const alias = formAlias.trim()
    const members = formMembers
      .map((member) => ({
        provider: member.provider.trim(),
        upstreamModel: member.upstreamModel.trim(),
        weight: Number(member.weight) || 1,
      }))
      .filter((member) => member.provider && member.upstreamModel)

    if (!alias || members.length === 0) {
      toast({ title: t.common.error, description: 'Alias and at least one member are required.', variant: 'error' })
      return null
    }

    return { members }
  }

  const handleSave = async () => {
    const alias = formAlias.trim()
    const payload = sanitizePayload()
    if (!payload || !alias) {
      return
    }

    setSaving(true)
    try {
      await modelGroups.upsert(alias, payload)
      toast({ title: TEXT.saveSuccess(alias), variant: 'success' })
      setDialogOpen(false)
      await loadGroups()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (alias: string) => {
    if (!window.confirm(t.common.deleteConfirm.replace('{name}', alias))) {
      return
    }
    try {
      await modelGroups.remove(alias)
      toast({ title: TEXT.deleteSuccess(alias), variant: 'success' })
      await loadGroups()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    }
  }

  if (loading) {
    return <LoadingScreen />
  }

  const entries = Object.entries(groups)

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{TEXT.title}</h1>
          <p className="text-sm text-muted-foreground">{TEXT.intro}</p>
          <p className="text-sm text-muted-foreground">{TEXT.count(entries.length)}</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {TEXT.add}
        </Button>
      </div>

      {entries.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">{TEXT.noData}</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{TEXT.alias}</TableHead>
                  <TableHead>Scene</TableHead>
                  <TableHead>{TEXT.members}</TableHead>
                  <TableHead>Summary</TableHead>
                  <TableHead className="text-right">{t.common.actions}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([alias, group]) => (
                  <TableRow key={alias}>
                    <TableCell className="font-medium">{alias}</TableCell>
                    <TableCell>{group.scene}</TableCell>
                    <TableCell>{TEXT.routeCount(group.members.length)}</TableCell>
                    <TableCell>
                      <div className="space-y-1 text-sm text-muted-foreground">
                        {group.members.map((member) => (
                          <p key={member.routeId}>
                            {member.provider} / {member.upstreamModel} / w={member.weight}
                          </p>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => openEdit(alias, group)} title={t.common.edit}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleDelete(alias)} title={t.common.delete}>
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

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editingAlias ? TEXT.edit : TEXT.add}</DialogTitle>
            <DialogDescription>{TEXT.dialogDesc}</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 max-h-[70vh] overflow-y-auto pr-1">
            <div className="space-y-2">
              <Label htmlFor="mg-alias">{TEXT.alias}</Label>
              <Input
                id="mg-alias"
                value={formAlias}
                onChange={(e) => setFormAlias(e.target.value)}
                placeholder={TEXT.aliasPlaceholder}
                disabled={Boolean(editingAlias)}
              />
            </div>

            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <Label>{TEXT.members}</Label>
                <Button type="button" variant="outline" size="sm" onClick={addMember}>
                  <Plus className="h-3.5 w-3.5" /> {TEXT.addMember}
                </Button>
              </div>

              {formMembers.map((member, index) => (
                <Card key={index}>
                  <CardContent className="space-y-3 pt-6">
                    <div className="flex items-center justify-between">
                      <p className="text-sm font-medium">Member {index + 1}</p>
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => removeMember(index)}
                        disabled={formMembers.length === 1}
                        title="Remove member"
                      >
                        <X className="h-4 w-4" />
                      </Button>
                    </div>

                    <div className="grid gap-3 md:grid-cols-3">
                      <div className="space-y-2">
                        <Label htmlFor={`provider-${index}`}>{TEXT.memberProvider}</Label>
                        <Input
                          id={`provider-${index}`}
                          value={member.provider}
                          onChange={(e) => updateMember(index, { provider: e.target.value })}
                          placeholder="openai-main"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor={`upstream-model-${index}`}>{TEXT.memberModel}</Label>
                        <Input
                          id={`upstream-model-${index}`}
                          value={member.upstreamModel}
                          onChange={(e) => updateMember(index, { upstreamModel: e.target.value })}
                          placeholder="gpt-4o-mini"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor={`weight-${index}`}>{TEXT.memberWeight}</Label>
                        <Input
                          id={`weight-${index}`}
                          type="number"
                          min="1"
                          value={String(member.weight)}
                          onChange={(e) => updateMember(index, { weight: Number(e.target.value) || 1 })}
                        />
                      </div>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>{t.common.cancel}</Button>
            <Button onClick={handleSave} loading={saving}>{t.common.save}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
