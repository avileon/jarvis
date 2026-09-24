import { StrictMode, Suspense, lazy } from 'react';
import { createRoot } from 'react-dom/client';

const isAdmin = location.pathname.startsWith('/admin');
const App = isAdmin ? lazy(() => import('./admin/Admin')) : lazy(() => import('./station/Station'));

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Suspense fallback={null}>
      <App />
    </Suspense>
  </StrictMode>,
);
