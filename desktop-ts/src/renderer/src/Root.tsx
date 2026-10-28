import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { ServicesProvider, type AppServices } from './api/ApiContext';
import { ToastProvider } from './components/Toasts';
import { App } from './App';

export function Root({ services, queryClient }: { services: AppServices; queryClient: QueryClient }) {
  return (
    <ServicesProvider services={services}>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <App />
        </ToastProvider>
      </QueryClientProvider>
    </ServicesProvider>
  );
}
