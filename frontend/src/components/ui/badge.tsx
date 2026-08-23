import { type HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

const badgeVariants = {
  default: 'border-transparent bg-primary text-primary-foreground',
  secondary: 'border-transparent bg-secondary text-secondary-foreground',
  destructive: 'border-transparent bg-destructive text-destructive-foreground',
  outline: 'text-foreground',
  success: 'border-transparent bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-100',
  warning: 'border-transparent bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-100',
}

export interface BadgeProps extends HTMLAttributes<HTMLDivElement> {
  variant?: keyof typeof badgeVariants
}

export function Badge({ className, variant = 'default', ...props }: BadgeProps) {
  return (
    <div
      className={cn(
        'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors',
        badgeVariants[variant],
        className,
      )}
      {...props}
    />
  )
}
