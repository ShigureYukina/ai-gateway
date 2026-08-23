import { useTranslation } from '@/i18n'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { toast } from '@/components/ui/toast'
import { Copy, Check } from 'lucide-react'
import { useState } from 'react'

function CopyButton({
  copied,
  idx,
  onCopy,
}: {
  copied: boolean
  idx: number
  onCopy: (idx: number) => void
}) {
  return (
    <Button variant="ghost" size="icon" onClick={() => onCopy(idx)}>
      {copied ? <Check className="h-4 w-4 text-emerald-500" /> : <Copy className="h-4 w-4" />}
    </Button>
  )
}

export default function UserOnboardingPage() {
  const { t } = useTranslation()
  const [copiedIdx, setCopiedIdx] = useState<number | null>(null)

  const copyText = async (text: string, idx: number) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopiedIdx(idx)
      toast({ title: t.common.copied, variant: 'success' })
      setTimeout(() => setCopiedIdx(null), 2000)
    } catch { /* ignore */ }
  }

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <h1 className="text-2xl font-bold">{t.userOnboarding.title}</h1>
      <p className="text-muted-foreground">{t.userOnboarding.description}</p>

      {/* Base URL */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{t.userOnboarding.baseUrl}</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-between p-3 bg-muted rounded-md">
            <code className="text-sm">{t.userOnboarding.baseUrlValue}</code>
            <CopyButton copied={copiedIdx === 0} idx={0} onCopy={(idx) => copyText(t.userOnboarding.baseUrlValue, idx)} />
          </div>
        </CardContent>
      </Card>

      {/* API Key */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{t.userOnboarding.apiKey}</CardTitle>
          <CardDescription>{t.userOnboarding.apiKeyHint}</CardDescription>
        </CardHeader>
      </Card>

      {/* Models */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{t.userOnboarding.models}</CardTitle>
          <CardDescription>{t.userOnboarding.modelsHint}</CardDescription>
        </CardHeader>
      </Card>

      {/* OpenAI SDK Example */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">{t.userOnboarding.codeSample}</CardTitle>
            <CopyButton copied={copiedIdx === 1} idx={1} onCopy={(idx) => copyText(t.userOnboarding.code, idx)} />
          </div>
        </CardHeader>
        <CardContent>
          <pre className="text-sm bg-muted p-4 rounded-md overflow-x-auto">
            <code>{t.userOnboarding.code}</code>
          </pre>
        </CardContent>
      </Card>

      {/* cURL Example */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">{t.userOnboarding.curlSample}</CardTitle>
            <CopyButton copied={copiedIdx === 2} idx={2} onCopy={(idx) => copyText(t.userOnboarding.curl, idx)} />
          </div>
        </CardHeader>
        <CardContent>
          <pre className="text-sm bg-muted p-4 rounded-md overflow-x-auto">
            <code>{t.userOnboarding.curl}</code>
          </pre>
        </CardContent>
      </Card>
    </div>
  )
}
