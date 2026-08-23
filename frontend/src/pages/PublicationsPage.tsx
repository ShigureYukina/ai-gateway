import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { toast } from '@/components/ui/toast'
import { useTranslation } from '@/i18n'
import type { PublicationResponse } from '@/types/api'

import { publications } from '@/api/modules/admin/publications'

const TEXT = {
  title: 'Publications',
  intro: 'Publish one public alias to a provider model and review the returned pricing summary.',
  alias: 'Alias',
  provider: 'Provider',
  upstreamModel: 'Upstream Model',
  publish: 'Publish Alias',
  result: 'Last publish result',
  warnings: 'Warnings',
  visibilityYes: 'Visible in /v1/models',
  visibilityNo: 'Not yet visible in /v1/models',
  success: (alias: string) => `Published alias ${alias}`,
}

export default function PublicationsPage() {
  const { t } = useTranslation()
  const [alias, setAlias] = useState('')
  const [provider, setProvider] = useState('')
  const [upstreamModel, setUpstreamModel] = useState('')
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<PublicationResponse | null>(null)

  const handlePublish = async () => {
    const normalizedAlias = alias.trim()
    const normalizedProvider = provider.trim()
    const normalizedUpstreamModel = upstreamModel.trim()

    if (!normalizedAlias || !normalizedProvider || !normalizedUpstreamModel) {
      toast({ title: t.common.error, description: 'Alias, provider and upstream model are required.', variant: 'error' })
      return
    }

    setSaving(true)
    try {
      const response = await publications.publish(normalizedAlias, {
        provider: normalizedProvider,
        upstreamModel: normalizedUpstreamModel,
      })
      setResult(response)
      toast({ title: TEXT.success(normalizedAlias), variant: 'success' })
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{TEXT.title}</h1>
        <p className="text-sm text-muted-foreground">{TEXT.intro}</p>
      </div>

      <Card>
        <CardContent className="space-y-4 pt-6">
          <div className="grid gap-4 md:grid-cols-3">
            <div className="space-y-2">
              <Label htmlFor="publication-alias">{TEXT.alias}</Label>
              <Input
                id="publication-alias"
                value={alias}
                onChange={(e) => setAlias(e.target.value)}
                placeholder="gpt-4o-mini"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="publication-provider">{TEXT.provider}</Label>
              <Input
                id="publication-provider"
                value={provider}
                onChange={(e) => setProvider(e.target.value)}
                placeholder="openai-main"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="publication-upstream-model">{TEXT.upstreamModel}</Label>
              <Input
                id="publication-upstream-model"
                value={upstreamModel}
                onChange={(e) => setUpstreamModel(e.target.value)}
                placeholder="gpt-4o-mini"
              />
            </div>
          </div>

          <div className="flex justify-end">
            <Button onClick={handlePublish} loading={saving}>{TEXT.publish}</Button>
          </div>
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardContent className="space-y-4 pt-6">
            <div>
              <h2 className="text-lg font-semibold">{TEXT.result}</h2>
              <p className="text-sm text-muted-foreground">
                {result.alias} → {result.provider} / {result.upstreamModel}
              </p>
            </div>

            <div className="grid gap-3 md:grid-cols-2">
              <div className="rounded-md border p-3 text-sm">
                <p className="font-medium">Visibility</p>
                <p className="text-muted-foreground">
                  {result.visibleInV1Models ? TEXT.visibilityYes : TEXT.visibilityNo}
                </p>
              </div>
              <div className="rounded-md border p-3 text-sm">
                <p className="font-medium">Pricing Summary</p>
                <p className="text-muted-foreground">{result.price.summary || '—'}</p>
              </div>
              <div className="rounded-md border p-3 text-sm">
                <p className="font-medium">Price Source</p>
                <p className="text-muted-foreground">{result.price.source || '—'}</p>
              </div>
              <div className="rounded-md border p-3 text-sm">
                <p className="font-medium">Matched Model</p>
                <p className="text-muted-foreground">{result.price.matchedModel || '—'}</p>
              </div>
            </div>

            {result.warnings.length > 0 && (
              <div className="space-y-2">
                <h3 className="text-sm font-semibold">{TEXT.warnings}</h3>
                <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">
                  {result.warnings.map((warning) => (
                    <li key={warning}>{warning}</li>
                  ))}
                </ul>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
