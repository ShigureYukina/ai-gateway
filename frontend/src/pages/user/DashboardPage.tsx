import { useEffect, useState } from 'react'
import { auth } from '@/api/client'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useTranslation } from '@/i18n'
import { LoadingScreen } from '@/components/ui/loading'
import type { UserMeResponse } from '@/types/api'

/* ── 局部文案常量（不修改共享 locale 文件） ── */
const LABELS = {
  quotaTitle: 'Quota Usage',
  dailyTokens: 'Daily Tokens',
  monthlyTokens: 'Monthly Tokens',
  dailyCost: 'Daily Cost',
  monthlyCost: 'Monthly Cost',
  unlimited: 'Unlimited',
  notSupported: 'Not Supported',
  usedOfLimit: '{used} / {limit}',
  usedOfUnlimited: '{used} / ∞',
  costUnit: '$',
  tokenUnit: 'tokens',
  loadFailed: 'Failed to load profile',
} as const

/** 格式化数字：带千分位 */
function fmtNum(n: number): string {
  return n.toLocaleString()
}

/** 格式化美元金额 */
function fmtCost(micros: number): string {
  return `$${(micros / 1_000_000).toFixed(4)}`
}

/** 计算使用百分比，返回 0~100 */
function pct(used: number, limit: number | null): number | null {
  if (limit === null || limit <= 0) return null
  return Math.min(100, Math.round((used / limit) * 100))
}

/** 进度条颜色 */
function barColor(percent: number | null): string {
  if (percent === null) return 'bg-primary/40'
  if (percent >= 90) return 'bg-red-500'
  if (percent >= 70) return 'bg-amber-500'
  return 'bg-emerald-500'
}

export default function UserDashboardPage() {
  const { t } = useTranslation()
  const [profile, setProfile] = useState<UserMeResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    auth.me()
      .then(setProfile)
      .catch((err) => {
        setError(err instanceof Error ? err.message : LABELS.loadFailed)
      })
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <LoadingScreen />

  if (error) {
    return (
      <div className="max-w-3xl mx-auto space-y-6">
        <h1 className="text-2xl font-bold">{t.dashboard.title}</h1>
        <Card>
          <CardContent className="pt-6">
            <p className="text-sm text-destructive">{error}</p>
          </CardContent>
        </Card>
      </div>
    )
  }

  const quota = profile?.quota

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <h1 className="text-2xl font-bold">{t.dashboard.title}</h1>

      {/* ── 用户基本信息 ── */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm text-muted-foreground">{t.login.username}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-xl font-semibold">{profile?.username}</p>
          </CardContent>
        </Card>
        {profile?.displayName && (
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-muted-foreground">{t.profile.displayName}</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xl font-semibold">{profile.displayName}</p>
            </CardContent>
          </Card>
        )}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm text-muted-foreground">{t.profile.role}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-xl font-semibold capitalize">{profile?.role}</p>
          </CardContent>
        </Card>
      </div>

      {/* ── 配额使用情况 ── */}
      {quota && (
        <div className="space-y-4">
          <h2 className="text-lg font-semibold">{LABELS.quotaTitle}</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">

            {/* Daily Tokens */}
            <QuotaCard
              label={LABELS.dailyTokens}
              used={quota.dailyTokensUsed}
              limit={quota.dailyTokensLimit}
              formatValue={fmtNum}
              unit={LABELS.tokenUnit}
            />

            {/* Monthly Tokens */}
            <QuotaCard
              label={LABELS.monthlyTokens}
              used={quota.monthlyTokensUsed}
              limit={quota.monthlyTokensLimit}
              formatValue={fmtNum}
              unit={LABELS.tokenUnit}
              notSupported={quota.monthlyUnsupported}
            />

            {/* Daily Cost */}
            <QuotaCard
              label={LABELS.dailyCost}
              used={quota.dailyCostUsed}
              limit={quota.dailyCostLimit}
              formatValue={fmtCost}
              unit=""
            />

            {/* Monthly Cost */}
            <QuotaCard
              label={LABELS.monthlyCost}
              used={quota.monthlyCostUsed}
              limit={quota.monthlyCostLimit}
              formatValue={fmtCost}
              unit=""
              notSupported={quota.monthlyUnsupported}
            />
          </div>
        </div>
      )}
    </div>
  )
}

/* ── 配额卡片子组件 ── */

interface QuotaCardProps {
  label: string
  used: number
  limit: number | null
  formatValue: (n: number) => string
  unit: string
  notSupported?: boolean
}

function QuotaCard({ label, used, limit, formatValue, unit, notSupported }: QuotaCardProps) {
  const percent = pct(used, limit)
  const isUnlimited = limit === null

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm text-muted-foreground">{label}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {notSupported ? (
          <p className="text-sm text-muted-foreground italic">{LABELS.notSupported}</p>
        ) : (
          <>
            <div className="flex items-baseline justify-between">
              <span className="text-xl font-semibold">
                {formatValue(used)}
                {unit && <span className="text-xs text-muted-foreground ml-1">{unit}</span>}
              </span>
              <span className="text-sm text-muted-foreground">
                {isUnlimited
                  ? LABELS.unlimited
                  : `of ${formatValue(limit!)}${unit ? ` ${unit}` : ''}`}
              </span>
            </div>
            {/* 进度条 */}
            <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
              <div
                className={`h-full rounded-full transition-all duration-300 ${barColor(percent)}`}
                style={{ width: `${percent ?? 100}%` }}
              />
            </div>
            <p className="text-xs text-muted-foreground text-right">
              {percent !== null ? `${percent}%` : LABELS.unlimited}
            </p>
          </>
        )}
      </CardContent>
    </Card>
  )
}
