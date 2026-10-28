import type { ReactNode } from 'react';
import { Button } from './Button';
import { Modal } from './Modal';

export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  cancelLabel = 'Не отменять',
  danger = false,
  pending = false,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  children?: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  danger?: boolean;
  pending?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Modal
      open={open}
      title={title}
      onClose={onClose}
      size="sm"
      closeDisabled={pending}
      footer={
        <>
          {/* Фокус по умолчанию — на безопасном действии */}
          <Button autoFocus onClick={onClose} disabled={pending}>
            {cancelLabel}
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} disabled={pending}>
            {pending ? 'Подождите…' : confirmLabel}
          </Button>
        </>
      }
    >
      {children}
    </Modal>
  );
}
