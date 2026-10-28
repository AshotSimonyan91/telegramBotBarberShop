import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBridgeServices } from './api/ApiContext';
import { createQueryClient } from './api/queries';
import { Root } from './Root';
import './styles.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('Не найден элемент #root');
}

createRoot(container).render(
  <StrictMode>
    <Root services={createBridgeServices(window.salon)} queryClient={createQueryClient()} />
  </StrictMode>,
);
