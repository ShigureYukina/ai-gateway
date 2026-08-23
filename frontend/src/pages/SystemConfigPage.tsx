import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from '@/i18n'
import { systemConfig, config } from '@/api/client'
import type {
  AuthConfigDto,
  ConcurrentLimitConfigDto,
  ConfigExportResponse,
  LimitConfigDto,
  LoadBalancerConfigDto,
  OperationalConfigDto,
  PricingConfigDto,
  ProviderHealthConfigDto,
  ResilienceConfigDto,
  SyncConfigDto,
  TraceConfigDto,
} from '@/types/api'
import type { Translations } from '@/i18n/locales/en'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/toast'
import { LoadingScreen } from '@/components/ui/loading'

/* ── 局部文案常量（不修改共享 locale 文件） ── */
const LOCAL_LABELS = {
  loadBalancer: 'Load Balancer',
  concurrentLimit: 'Concurrent Limit',
  tracing: 'Tracing',
  sync: 'Sync (models.dev)',
  providerHealth: 'Provider Health',
  auth: 'Auth',
  loadBalancerTitle: 'Load Balancer Configuration',
  concurrentLimitTitle: 'Concurrent Request Limit',
  tracingTitle: 'Tracing / Observability',
  syncTitle: 'Models.dev Sync',
  providerHealthTitle: 'Provider Health Check',
  authTitle: 'Authentication Settings',
  lbEnabled: 'Enabled',
  lbHint: 'Enable weighted round-robin load balancing across upstream routes',
  clEnabled: 'Enabled',
  clMaxPerClient: 'Max Concurrent Per Client',
  clMaxGlobal: 'Max Concurrent (Global)',
  clHint: 'Limit in-flight requests per client and globally',
  traceEnabled: 'Enabled',
  traceMaxBodySize: 'Max Body Size (bytes)',
  traceSampleRate: 'Sample Rate (0~1)',
  traceHint: 'Configure request tracing and sampling',
  syncEnabled: 'Enabled',
  syncEndpoint: 'Endpoint',
  syncRefreshInterval: 'Refresh Interval',
  syncTimeout: 'Timeout',
  syncRunOnStartup: 'Run On Startup',
  syncPreferRemotePricing: 'Prefer Remote Pricing',
  syncHint: 'Configure models.dev catalog synchronization',
  phEnabled: 'Enabled',
  phRefreshInterval: 'Refresh Interval',
  phRunOnStartup: 'Run On Startup',
  phDisableAfter: 'Disable After Consecutive Failures',
  phRecoverAfter: 'Recover After Consecutive Successes',
  phHint: 'Periodically check upstream provider health and auto-disable failing providers',
  authEnabled: 'Auth Enabled',
  authRegistrationMode: 'Registration Mode',
  authAllowedModels: 'Allowed Models (comma separated)',
  authAllowedScenes: 'Allowed Scenes (comma separated)',
  authHint: 'Configure authentication and self-registration policies',
  regModeRestricted: 'restricted',
  regModeOpen: 'open',
  save: 'Save',
} as const

const SECTIONS: Record<string, string> = {
  rateLimits: 'Rate Limits',
  resilience: 'Resilience',
  pricing: 'Pricing',
  operational: 'Operational',
  loadBalancer: LOCAL_LABELS.loadBalancer,
  concurrentLimit: LOCAL_LABELS.concurrentLimit,
  tracing: LOCAL_LABELS.tracing,
  sync: LOCAL_LABELS.sync,
  providerHealth: LOCAL_LABELS.providerHealth,
  auth: LOCAL_LABELS.auth,
}

type I18nText = Translations

export default function SystemConfigPage() {
  const { t } = useTranslation()
  const { data: exportData, isLoading } = useQuery<ConfigExportResponse>({
    queryKey: ['configExport'],
    queryFn: () => config.export(),
  })

  const system = exportData?.system

  if (isLoading) return <LoadingScreen />

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t.systemConfig.title}</h1>
        <p className="text-sm text-muted-foreground">{t.systemConfig.subtitle}</p>
      </div>

      <Tabs defaultValue="limit">
        <TabsList className="flex flex-wrap">
          <TabsTrigger value="limit">{t.systemConfig.rateLimits}</TabsTrigger>
          <TabsTrigger value="resilience">{t.systemConfig.resilience}</TabsTrigger>
          <TabsTrigger value="pricing">{t.systemConfig.pricing}</TabsTrigger>
          <TabsTrigger value="operational">{t.systemConfig.operational}</TabsTrigger>
          <TabsTrigger value="loadBalancer">{LOCAL_LABELS.loadBalancer}</TabsTrigger>
          <TabsTrigger value="concurrentLimit">{LOCAL_LABELS.concurrentLimit}</TabsTrigger>
          <TabsTrigger value="tracing">{LOCAL_LABELS.tracing}</TabsTrigger>
          <TabsTrigger value="sync">{LOCAL_LABELS.sync}</TabsTrigger>
          <TabsTrigger value="providerHealth">{LOCAL_LABELS.providerHealth}</TabsTrigger>
          <TabsTrigger value="auth">{LOCAL_LABELS.auth}</TabsTrigger>
        </TabsList>

        <TabsContent value="limit">
          <LimitConfig t={t} initialData={system?.limit} />
        </TabsContent>
        <TabsContent value="resilience">
          <ResilienceConfig t={t} initialData={system?.resilience} />
        </TabsContent>
        <TabsContent value="pricing">
          <PricingConfig t={t} initialData={system?.pricing} />
        </TabsContent>
        <TabsContent value="operational">
          <OperationalConfig t={t} initialData={system?.operational} />
        </TabsContent>
        <TabsContent value="loadBalancer">
          <LoadBalancerConfig t={t} initialData={system?.loadBalancer} />
        </TabsContent>
        <TabsContent value="concurrentLimit">
          <ConcurrentLimitConfigSection t={t} initialData={system?.concurrentLimit} />
        </TabsContent>
        <TabsContent value="tracing">
          <TracingConfigSection t={t} initialData={system?.tracing} />
        </TabsContent>
        <TabsContent value="sync">
          <SyncConfigSection t={t} initialData={system?.sync} />
        </TabsContent>
        <TabsContent value="providerHealth">
          <ProviderHealthConfigSection t={t} initialData={system?.providerHealth} />
        </TabsContent>
        <TabsContent value="auth">
          <AuthConfigSection t={t} initialData={system?.auth} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

/* ── 通用保存逻辑 ── */

async function saveSection<T>(
  sectionName: string,
  saveFn: () => Promise<T>,
  t: I18nText,
) {
  try {
    await saveFn()
    toast({ title: t.systemConfig.savedSuccess.replace('{section}', SECTIONS[sectionName] ?? sectionName), variant: 'success' })
  } catch (error) {
    const message = error instanceof Error ? error.message : t.common.error
    toast({ title: t.systemConfig.saveFailed, description: message, variant: 'error' })
  }
}

/* ── 已有配置组件（保持原样） ── */

function LimitConfig({ t, initialData }: { t: I18nText; initialData?: LimitConfigDto }) {
  const [requestsPerWindow, setRequestsPerWindow] = useState(String(initialData?.requestsPerWindow ?? '10000'))
  const [window, setWindow] = useState(initialData?.window ?? '1m')
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('rateLimits', () =>
      systemConfig.updateLimit({
        requestsPerWindow: parseInt(requestsPerWindow),
        window,
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t.systemConfig.limitTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{t.systemConfig.maxRequestsPerWindow}</Label>
            <Input type="number" value={requestsPerWindow} onChange={(e) => setRequestsPerWindow(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>{t.systemConfig.windowDuration}</Label>
            <Input value={window} onChange={(e) => setWindow(e.target.value)} placeholder="PT1M" />
            <p className="text-xs text-muted-foreground">{t.systemConfig.windowHint}</p>
          </div>
        </div>
        <Button onClick={handleSave} loading={saving}>{t.systemConfig.saveRateLimits}</Button>
      </CardContent>
    </Card>
  )
}

function ResilienceConfig({ t, initialData }: { t: I18nText; initialData?: ResilienceConfigDto }) {
  const [maxAttempts, setMaxAttempts] = useState(String(initialData?.maxAttempts ?? '2'))
  const [threshold, setThreshold] = useState(String(initialData?.retryableFailureThreshold ?? '2'))
  const [failureWindow, setFailureWindow] = useState(initialData?.failureWindow ?? 'PT30S')
  const [openDuration, setOpenDuration] = useState(initialData?.openDuration ?? 'PT30S')
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('resilience', () =>
      systemConfig.updateResilience({
        maxAttempts: parseInt(maxAttempts),
        retryableFailureThreshold: parseInt(threshold),
        failureWindow,
        openDuration,
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t.systemConfig.resilienceTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{t.systemConfig.maxAttempts}</Label>
            <Input type="number" value={maxAttempts} onChange={(e) => setMaxAttempts(e.target.value)} min="1" />
          </div>
          <div className="space-y-2">
            <Label>{t.systemConfig.failureThreshold}</Label>
            <Input type="number" value={threshold} onChange={(e) => setThreshold(e.target.value)} min="1" />
          </div>
          <div className="space-y-2">
            <Label>{t.systemConfig.failureWindow}</Label>
            <Input value={failureWindow} onChange={(e) => setFailureWindow(e.target.value)} placeholder="PT30S" />
          </div>
          <div className="space-y-2">
            <Label>{t.systemConfig.openDuration}</Label>
            <Input value={openDuration} onChange={(e) => setOpenDuration(e.target.value)} placeholder="PT30S" />
          </div>
        </div>
        <Button onClick={handleSave} loading={saving}>{t.systemConfig.saveResilience}</Button>
      </CardContent>
    </Card>
  )
}

function PricingConfig({ t, initialData }: { t: I18nText; initialData?: PricingConfigDto }) {
  const [pricingText, setPricingText] = useState(() => {
    if (!initialData) return ''
    return JSON.stringify(initialData, null, 2)
  })
  const [saving, setSaving] = useState(false)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t.systemConfig.pricingTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">{t.systemConfig.pricingHint}</p>
        <div className="space-y-2">
          <Label>{t.systemConfig.pricingJson}</Label>
          <textarea
            className="flex min-h-[120px] w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-xs"
            value={pricingText}
            onChange={(e) => setPricingText(e.target.value)}
            placeholder={t.systemConfig.pricingPlaceholder}
          />
        </div>
        <Button onClick={async () => {
          setSaving(true)
          try {
            const data: PricingConfigDto = pricingText ? JSON.parse(pricingText) : {}
            await systemConfig.updatePricing(data)
            toast({ title: t.systemConfig.savedSuccess.replace('{section}', SECTIONS.pricing), variant: 'success' })
          } catch (error) {
            const message = error instanceof Error ? error.message : t.common.error
            toast({ title: t.systemConfig.invalidJson, description: message, variant: 'error' })
          } finally {
            setSaving(false)
          }
        }} loading={saving}>{t.systemConfig.savePricing}</Button>
      </CardContent>
    </Card>
  )
}

function OperationalConfig({ t, initialData }: { t: I18nText; initialData?: OperationalConfigDto }) {
  const [maintenanceMode, setMaintenanceMode] = useState(initialData?.maintenanceMode ?? false)
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('operational', () =>
      systemConfig.updateOperational({ maintenanceMode }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t.systemConfig.operationalTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={maintenanceMode} onCheckedChange={setMaintenanceMode} label={t.systemConfig.maintenanceMode} />
        <p className="text-xs text-muted-foreground">{t.systemConfig.maintenanceHint}</p>
        <Button onClick={handleSave} loading={saving}>{t.systemConfig.saveOperational}</Button>
      </CardContent>
    </Card>
  )
}

/* ── 新增配置组件 ── */

function LoadBalancerConfig({ t, initialData }: { t: I18nText; initialData?: LoadBalancerConfigDto }) {
  const [enabled, setEnabled] = useState(initialData?.enabled ?? false)
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('loadBalancer', () =>
      systemConfig.updateLoadBalancer({ enabled }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{LOCAL_LABELS.loadBalancerTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={enabled} onCheckedChange={setEnabled} label={LOCAL_LABELS.lbEnabled} />
        <p className="text-xs text-muted-foreground">{LOCAL_LABELS.lbHint}</p>
        <Button onClick={handleSave} loading={saving}>{LOCAL_LABELS.save}</Button>
      </CardContent>
    </Card>
  )
}

function ConcurrentLimitConfigSection({ t, initialData }: { t: I18nText; initialData?: ConcurrentLimitConfigDto }) {
  const [enabled, setEnabled] = useState(initialData?.enabled ?? false)
  const [maxPerClient, setMaxPerClient] = useState(String(initialData?.maxPerClient ?? 10))
  const [maxGlobal, setMaxGlobal] = useState(String(initialData?.maxGlobal ?? 200))
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('concurrentLimit', () =>
      systemConfig.updateConcurrentLimit({
        enabled,
        maxPerClient: parseInt(maxPerClient),
        maxGlobal: parseInt(maxGlobal),
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{LOCAL_LABELS.concurrentLimitTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={enabled} onCheckedChange={setEnabled} label={LOCAL_LABELS.clEnabled} />
        <p className="text-xs text-muted-foreground">{LOCAL_LABELS.clHint}</p>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.clMaxPerClient}</Label>
            <Input type="number" value={maxPerClient} onChange={(e) => setMaxPerClient(e.target.value)} min="1" />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.clMaxGlobal}</Label>
            <Input type="number" value={maxGlobal} onChange={(e) => setMaxGlobal(e.target.value)} min="1" />
          </div>
        </div>
        <Button onClick={handleSave} loading={saving}>{LOCAL_LABELS.save}</Button>
      </CardContent>
    </Card>
  )
}

function TracingConfigSection({ t, initialData }: { t: I18nText; initialData?: TraceConfigDto }) {
  const [enabled, setEnabled] = useState(initialData?.enabled ?? false)
  const [maxBodySize, setMaxBodySize] = useState(String(initialData?.maxBodySize ?? 16384))
  const [sampleRate, setSampleRate] = useState(String(initialData?.sampleRate ?? 1.0))
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('tracing', () =>
      systemConfig.updateTracing({
        enabled,
        maxBodySize: parseInt(maxBodySize),
        sampleRate: parseFloat(sampleRate),
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{LOCAL_LABELS.tracingTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={enabled} onCheckedChange={setEnabled} label={LOCAL_LABELS.traceEnabled} />
        <p className="text-xs text-muted-foreground">{LOCAL_LABELS.traceHint}</p>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.traceMaxBodySize}</Label>
            <Input type="number" value={maxBodySize} onChange={(e) => setMaxBodySize(e.target.value)} min="0" />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.traceSampleRate}</Label>
            <Input type="number" value={sampleRate} onChange={(e) => setSampleRate(e.target.value)} min="0" max="1" step="0.1" />
          </div>
        </div>
        <Button onClick={handleSave} loading={saving}>{LOCAL_LABELS.save}</Button>
      </CardContent>
    </Card>
  )
}

function SyncConfigSection({ t, initialData }: { t: I18nText; initialData?: SyncConfigDto }) {
  const md = initialData?.modelsDev
  const [enabled, setEnabled] = useState(md?.enabled ?? false)
  const [endpoint, setEndpoint] = useState(md?.endpoint ?? 'https://models.dev/api.json')
  const [refreshInterval, setRefreshInterval] = useState(md?.refreshInterval ?? 'PT30M')
  const [timeout, setTimeout_] = useState(md?.timeout ?? 'PT5S')
  const [runOnStartup, setRunOnStartup] = useState(md?.runOnStartup ?? true)
  const [preferRemotePricing, setPreferRemotePricing] = useState(md?.preferRemotePricing ?? true)
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('sync', () =>
      systemConfig.updateSync({
        modelsDev: {
          enabled,
          endpoint,
          refreshInterval,
          timeout,
          runOnStartup,
          preferRemotePricing,
        },
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{LOCAL_LABELS.syncTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={enabled} onCheckedChange={setEnabled} label={LOCAL_LABELS.syncEnabled} />
        <p className="text-xs text-muted-foreground">{LOCAL_LABELS.syncHint}</p>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.syncEndpoint}</Label>
            <Input value={endpoint} onChange={(e) => setEndpoint(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.syncRefreshInterval}</Label>
            <Input value={refreshInterval} onChange={(e) => setRefreshInterval(e.target.value)} placeholder="PT30M" />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.syncTimeout}</Label>
            <Input value={timeout} onChange={(e) => setTimeout_(e.target.value)} placeholder="PT5S" />
          </div>
        </div>
        <div className="flex flex-col gap-3">
          <Switch checked={runOnStartup} onCheckedChange={setRunOnStartup} label={LOCAL_LABELS.syncRunOnStartup} />
          <Switch checked={preferRemotePricing} onCheckedChange={setPreferRemotePricing} label={LOCAL_LABELS.syncPreferRemotePricing} />
        </div>
        <Button onClick={handleSave} loading={saving}>{LOCAL_LABELS.save}</Button>
      </CardContent>
    </Card>
  )
}

function ProviderHealthConfigSection({ t, initialData }: { t: I18nText; initialData?: ProviderHealthConfigDto }) {
  const [enabled, setEnabled] = useState(initialData?.enabled ?? false)
  const [refreshInterval, setRefreshInterval] = useState(initialData?.refreshInterval ?? 'PT5M')
  const [runOnStartup, setRunOnStartup] = useState(initialData?.runOnStartup ?? true)
  const [disableAfter, setDisableAfter] = useState(String(initialData?.disableAfterConsecutiveFailures ?? 3))
  const [recoverAfter, setRecoverAfter] = useState(String(initialData?.recoverAfterConsecutiveSuccesses ?? 2))
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('providerHealth', () =>
      systemConfig.updateProviderHealth({
        enabled,
        refreshInterval,
        runOnStartup,
        disableAfterConsecutiveFailures: parseInt(disableAfter),
        recoverAfterConsecutiveSuccesses: parseInt(recoverAfter),
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{LOCAL_LABELS.providerHealthTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={enabled} onCheckedChange={setEnabled} label={LOCAL_LABELS.phEnabled} />
        <p className="text-xs text-muted-foreground">{LOCAL_LABELS.phHint}</p>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.phRefreshInterval}</Label>
            <Input value={refreshInterval} onChange={(e) => setRefreshInterval(e.target.value)} placeholder="PT5M" />
          </div>
          <div className="space-y-2">
            <Switch checked={runOnStartup} onCheckedChange={setRunOnStartup} label={LOCAL_LABELS.phRunOnStartup} />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.phDisableAfter}</Label>
            <Input type="number" value={disableAfter} onChange={(e) => setDisableAfter(e.target.value)} min="1" />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.phRecoverAfter}</Label>
            <Input type="number" value={recoverAfter} onChange={(e) => setRecoverAfter(e.target.value)} min="1" />
          </div>
        </div>
        <Button onClick={handleSave} loading={saving}>{LOCAL_LABELS.save}</Button>
      </CardContent>
    </Card>
  )
}

function AuthConfigSection({ t, initialData }: { t: I18nText; initialData?: AuthConfigDto }) {
  const reg = initialData?.registration
  const [enabled, setEnabled] = useState(initialData?.enabled ?? false)
  const [registrationMode, setRegistrationMode] = useState(initialData?.registrationMode ?? 'restricted')
  const [allowedModels, setAllowedModels] = useState(reg?.allowedModels?.join(', ') ?? 'gpt-4o-mini')
  const [allowedScenes, setAllowedScenes] = useState(reg?.allowedScenes?.join(', ') ?? 'default-chat')
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    await saveSection('auth', () =>
      systemConfig.updateAuth({
        enabled,
        registrationMode,
        registration: {
          allowedModels: allowedModels.split(',').map((s) => s.trim()).filter(Boolean),
          allowedScenes: allowedScenes.split(',').map((s) => s.trim()).filter(Boolean),
        },
      }),
      t,
    )
    setSaving(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{LOCAL_LABELS.authTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Switch checked={enabled} onCheckedChange={setEnabled} label={LOCAL_LABELS.authEnabled} />
        <p className="text-xs text-muted-foreground">{LOCAL_LABELS.authHint}</p>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.authRegistrationMode}</Label>
            <select
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs"
              value={registrationMode}
              onChange={(e) => setRegistrationMode(e.target.value)}
            >
              <option value="restricted">{LOCAL_LABELS.regModeRestricted}</option>
              <option value="open">{LOCAL_LABELS.regModeOpen}</option>
            </select>
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.authAllowedModels}</Label>
            <Input value={allowedModels} onChange={(e) => setAllowedModels(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>{LOCAL_LABELS.authAllowedScenes}</Label>
            <Input value={allowedScenes} onChange={(e) => setAllowedScenes(e.target.value)} />
          </div>
        </div>
        <Button onClick={handleSave} loading={saving}>{LOCAL_LABELS.save}</Button>
      </CardContent>
    </Card>
  )
}
