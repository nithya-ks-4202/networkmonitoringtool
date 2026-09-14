import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'
import './styles/app.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Monitoring data is always slightly stale by nature; refetching on every
      // window focus would hammer the API for data that refreshes on a timer
      // anyway.
      refetchOnWindowFocus: false,
      staleTime: 10_000,
      // One retry: a transient blip is worth retrying, a real outage is not
      // worth hiding behind a spinner that never resolves.
      retry: 1,
    },
  },
})

const container = document.getElementById('root')
if (!container) {
  throw new Error('No #root element; index.html is not the document being served')
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
