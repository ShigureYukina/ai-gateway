import { createContext, useContext, useState, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

interface TabsContextType {
  value: string
  onValueChange: (value: string) => void
}

const TabsContext = createContext<TabsContextType | null>(null)

export function Tabs({
  value: controlledValue,
  defaultValue,
  onValueChange,
  children,
  className,
}: {
  value?: string
  defaultValue?: string
  onValueChange?: (value: string) => void
  children: ReactNode
  className?: string
}) {
  const [internalValue, setInternalValue] = useState(defaultValue || '')
  const isControlled = controlledValue !== undefined
  const currentValue = isControlled ? controlledValue : internalValue

  const handleChange = (newValue: string) => {
    if (!isControlled) setInternalValue(newValue)
    onValueChange?.(newValue)
  }

  return (
    <TabsContext.Provider value={{ value: currentValue, onValueChange: handleChange }}>
      <div className={className}>{children}</div>
    </TabsContext.Provider>
  )
}

export function TabsList({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        'inline-flex h-9 items-center justify-center rounded-lg bg-muted p-1 text-muted-foreground',
        className,
      )}
    >
      {children}
    </div>
  )
}

export function TabsTrigger({
  value,
  children,
  className,
}: {
  value: string
  children: ReactNode
  className?: string
}) {
  const ctx = useContext(TabsContext)
  if (!ctx) throw new Error('TabsTrigger must be used within Tabs')
  const active = ctx.value === value

  return (
    <button
      onClick={() => ctx.onValueChange(value)}
      className={cn(
        'inline-flex items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-sm font-medium ring-offset-background transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50',
        active && 'bg-background text-foreground shadow-sm',
        !active && 'hover:text-foreground',
        className,
      )}
    >
      {children}
    </button>
  )
}

export function TabsContent({
  value,
  children,
  className,
}: {
  value: string
  children: ReactNode
  className?: string
}) {
  const ctx = useContext(TabsContext)
  if (!ctx) throw new Error('TabsContent must be used within Tabs')

  if (ctx.value !== value) return null

  return (
    <div
      className={cn(
        'mt-2 ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        className,
      )}
    >
      {children}
    </div>
  )
}
