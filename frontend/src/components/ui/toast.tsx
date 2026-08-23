import { useState, useEffect, createContext, useContext, useCallback, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { X, CheckCircle, AlertCircle, Info } from 'lucide-react'

interface Toast {
  id: string
  title: string
  description?: string
  variant?: 'success' | 'error' | 'info'
}

interface ToastContextType {
  addToast: (toast: Omit<Toast, 'id'>) => void
}

const ToastContext = createContext<ToastContextType | null>(null)

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}

export function toast(props: Omit<Toast, 'id'>) {
  // This is a simple implementation - in a real app, use a event bus
  const event = new CustomEvent('toast', { detail: props })
  window.dispatchEvent(event)
}

// Internal hook for Toaster
function useToaster() {
  const [toasts, setToasts] = useState<Toast[]>([])

  const addToast = useCallback((props: Omit<Toast, 'id'>) => {
    const id = Math.random().toString(36).slice(2)
    setToasts((prev) => [...prev, { ...props, id }])
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 4000)
  }, [])

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  useEffect(() => {
    const handler = (e: CustomEvent) => {
      addToast(e.detail)
    }
    window.addEventListener('toast' as any, handler as any)
    return () => window.removeEventListener('toast' as any, handler as any)
  }, [addToast])

  return { toasts, addToast, removeToast }
}

export function Toaster({ children }: { children?: ReactNode }) {
  const { toasts, addToast, removeToast } = useToaster()

  const icons = {
    success: <CheckCircle className="h-5 w-5 text-emerald-500" />,
    error: <AlertCircle className="h-5 w-5 text-destructive" />,
    info: <Info className="h-5 w-5 text-primary" />,
  }

  return (
    <ToastContext.Provider value={{ addToast }}>
      {children}
      <div className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 max-w-sm">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={cn(
              'flex items-start gap-3 rounded-lg border bg-background p-4 shadow-lg',
              'animate-in slide-in-from-right-5 fade-in-0 duration-200',
            )}
          >
            {icons[t.variant || 'info']}
            <div className="flex-1">
              <p className="text-sm font-semibold">{t.title}</p>
              {t.description && <p className="text-xs text-muted-foreground mt-1">{t.description}</p>}
            </div>
            <button onClick={() => removeToast(t.id)} className="opacity-50 hover:opacity-100">
              <X className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
