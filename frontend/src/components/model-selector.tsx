import { useState } from 'react'
import { useTranslation } from '@/i18n'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { Plus, X, Search } from 'lucide-react'

interface ModelSelectorProps {
  /** All available models fetched from upstream */
  availableModels: string[]
  /** Currently selected model names */
  selectedModels: string[]
  /** Called when the selection changes */
  onChange: (models: string[]) => void
  /** Called when user wants to re-fetch models from upstream */
  onFetchModels?: () => void
  /** Whether a fetch is in progress */
  fetching?: boolean
  /** Loading state for available models */
  loading?: boolean
}

export function ModelSelector({
  availableModels,
  selectedModels,
  onChange,
  onFetchModels,
  fetching,
}: ModelSelectorProps) {
  const { t } = useTranslation()
  const [customInput, setCustomInput] = useState('')
  const [searchQuery, setSearchQuery] = useState('')

  const selectedSet = new Set(selectedModels)
  const availableSet = new Set(availableModels)

  const toggleModel = (model: string) => {
    if (selectedSet.has(model)) {
      onChange(selectedModels.filter((m) => m !== model))
    } else {
      onChange([...selectedModels, model])
    }
  }

  const addCustomModel = () => {
    const name = customInput.trim()
    if (!name) return
    if (!selectedSet.has(name)) {
      onChange([...selectedModels, name])
    }
    setCustomInput('')
  }

  const removeModel = (model: string) => {
    onChange(selectedModels.filter((m) => m !== model))
  }

  const filteredAvailable = availableModels.filter(
    (m) => !searchQuery || m.toLowerCase().includes(searchQuery.toLowerCase()),
  )

  // Separate selected into: those from available list + custom ones
  const customSelected = selectedModels.filter((m) => !availableSet.has(m))

  return (
    <div className="space-y-3">
      {/* Header with fetch button */}
      <div className="flex items-center justify-between">
        <Label>{t.providers.fieldModels}</Label>
        {onFetchModels && (
          <Button variant="outline" size="sm" onClick={onFetchModels} loading={fetching}>
            <Search className="h-3.5 w-3.5" />
            {t.providers.fetchModels}
          </Button>
        )}
      </div>

      {/* Selected models as tags */}
      {selectedModels.length > 0 && (
        <div className="flex flex-wrap gap-1.5 p-2 rounded-md border bg-muted/30 min-h-[36px]">
          {selectedModels.map((model) => (
            <Badge key={model} variant="secondary" className="gap-1 pr-1">
              {model}
              <button
                onClick={() => removeModel(model)}
                className="ml-0.5 rounded-full hover:bg-muted-foreground/20 p-0.5"
              >
                <X className="h-3 w-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}

      {/* Available models list (from upstream) */}
      {availableModels.length > 0 && (
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <Input
              placeholder={t.providers.filterModels || 'Filter models...'}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="h-8 text-xs"
            />
          </div>
          <div className="max-h-[180px] overflow-y-auto space-y-0.5 rounded-md border">
            {filteredAvailable.length === 0 ? (
              <p className="text-xs text-muted-foreground p-2">{t.common.noData}</p>
            ) : (
              filteredAvailable.map((model) => {
                const selected = selectedSet.has(model)
                return (
                  <button
                    key={model}
                    type="button"
                    onClick={() => toggleModel(model)}
                    className={cn(
                      'w-full text-left px-3 py-1.5 text-xs transition-colors flex items-center gap-2',
                      'hover:bg-accent',
                      selected && 'bg-primary/5 font-medium',
                    )}
                  >
                    <div
                      className={cn(
                        'w-3.5 h-3.5 rounded border flex items-center justify-center shrink-0',
                        selected ? 'bg-primary border-primary' : 'border-muted-foreground/30',
                      )}
                    >
                      {selected && (
                        <svg className="w-2.5 h-2.5 text-primary-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </div>
                    <span className="truncate">{model}</span>
                  </button>
                )
              })
            )}
          </div>
        </div>
      )}

      {/* Custom model input */}
      {availableModels.length > 0 && (
        <div className="h-px bg-border" />
      )}
      <div className="flex gap-2">
        <Input
          value={customInput}
          onChange={(e) => setCustomInput(e.target.value)}
          placeholder={t.providers.addModel || 'Add custom model...'}
          className="h-8 text-xs flex-1"
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              addCustomModel()
            }
          }}
        />
        <Button type="button" variant="outline" size="sm" onClick={addCustomModel} disabled={!customInput.trim()}>
          <Plus className="h-3.5 w-3.5" />
        </Button>
      </div>

      {/* Count display */}
      {selectedModels.length > 1 && (
        <p className="text-xs text-muted-foreground">
          {selectedModels.length} models selected
          {customSelected.length > 0 && ` (${customSelected.length} custom)`}
        </p>
      )}
    </div>
  )
}

function Label({ children }: { children: React.ReactNode }) {
  return <p className="text-sm font-medium leading-none">{children}</p>
}
