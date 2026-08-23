import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { Plus, X } from 'lucide-react'

interface TagSelectorProps {
  label: string
  /** All available options to show as checklist */
  options: string[]
  /** Currently selected values */
  selected: string[]
  /** Called when selection changes */
  onChange: (selected: string[]) => void
  /** Placeholder for the custom input */
  inputPlaceholder?: string
  /** Placeholder for the search input */
  searchPlaceholder?: string
}

export function TagSelector({
  label,
  options,
  selected,
  onChange,
  inputPlaceholder = 'Add custom...',
  searchPlaceholder = 'Filter...',
}: TagSelectorProps) {
  const [customInput, setCustomInput] = useState('')
  const [searchQuery, setSearchQuery] = useState('')

  const selectedSet = new Set(selected)

  const toggle = (value: string) => {
    if (selectedSet.has(value)) {
      onChange(selected.filter((v) => v !== value))
    } else {
      onChange([...selected, value])
    }
  }

  const addCustom = () => {
    const v = customInput.trim()
    if (!v) return
    if (!selectedSet.has(v)) {
      onChange([...selected, v])
    }
    setCustomInput('')
  }

  const remove = (value: string) => {
    onChange(selected.filter((v) => v !== value))
  }

  const filtered = options.filter(
    (o) => !searchQuery || o.toLowerCase().includes(searchQuery.toLowerCase()),
  )

  return (
    <div className="space-y-3">
      <p className="text-sm font-medium leading-none">{label}</p>

      {/* Selected tags */}
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1.5 p-2 rounded-md border bg-muted/30 min-h-[36px]">
          {selected.map((value) => (
            <Badge key={value} variant="secondary" className="gap-1 pr-1">
              {value}
              <button onClick={() => remove(value)} className="ml-0.5 rounded-full hover:bg-muted-foreground/20 p-0.5">
                <X className="h-3 w-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}

      {/* Option checklist */}
      {options.length > 0 && (
        <div className="space-y-1.5">
          <Input
            placeholder={searchPlaceholder}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="h-8 text-xs"
          />
          <div className="max-h-[180px] overflow-y-auto space-y-0.5 rounded-md border">
            {filtered.length === 0 ? (
              <p className="text-xs text-muted-foreground p-2">No options</p>
            ) : (
              filtered.map((value) => {
                const sel = selectedSet.has(value)
                return (
                  <button
                    key={value}
                    type="button"
                    onClick={() => toggle(value)}
                    className={cn(
                      'w-full text-left px-3 py-1.5 text-xs transition-colors flex items-center gap-2',
                      'hover:bg-accent',
                      sel && 'bg-primary/5 font-medium',
                    )}
                  >
                    <div
                      className={cn(
                        'w-3.5 h-3.5 rounded border flex items-center justify-center shrink-0',
                        sel ? 'bg-primary border-primary' : 'border-muted-foreground/30',
                      )}
                    >
                      {sel && (
                        <svg className="w-2.5 h-2.5 text-primary-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </div>
                    <span className="truncate">{value}</span>
                  </button>
                )
              })
            )}
          </div>
        </div>
      )}

      {options.length > 0 && <div className="h-px bg-border" />}

      {/* Custom input */}
      <div className="flex gap-2">
        <Input
          value={customInput}
          onChange={(e) => setCustomInput(e.target.value)}
          placeholder={inputPlaceholder}
          className="h-8 text-xs flex-1"
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); addCustom() }
          }}
        />
        <button
          type="button"
          onClick={addCustom}
          disabled={!customInput.trim()}
          className="inline-flex items-center justify-center rounded-md border h-8 w-8 shrink-0 disabled:opacity-50 hover:bg-accent"
        >
          <Plus className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  )
}
