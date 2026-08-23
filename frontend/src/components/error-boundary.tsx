import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught an unhandled error:', error.message, errorInfo.componentStack)
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null })
  }

  handleGoHome = () => {
    this.setState({ hasError: false, error: null })
    window.location.href = '/'
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen items-center justify-center p-4">
          <Card className="w-full max-w-md">
            <CardHeader>
              <CardTitle>Something went wrong</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">
                {this.state.error?.message || 'An unexpected error occurred while rendering this page.'}
              </p>
            </CardContent>
            <CardFooter className="gap-3">
              <Button onClick={this.handleRetry}>Try Again</Button>
              <Button variant="outline" onClick={this.handleGoHome}>Go Home</Button>
            </CardFooter>
          </Card>
        </div>
      )
    }
    return this.props.children
  }
}
