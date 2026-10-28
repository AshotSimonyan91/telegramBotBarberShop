import type { ComponentProps } from 'react';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';

const VARIANTS: Readonly<Record<Variant, string>> = {
  primary:
    'bg-indigo-600 text-white hover:bg-indigo-700 disabled:bg-indigo-600/50 dark:bg-indigo-500 dark:hover:bg-indigo-400 dark:text-slate-950',
  secondary:
    'border border-slate-300 bg-white text-slate-800 hover:bg-slate-100 disabled:text-slate-400 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700 dark:disabled:text-slate-500',
  danger:
    'bg-red-600 text-white hover:bg-red-700 disabled:bg-red-600/50 dark:bg-red-500 dark:hover:bg-red-400 dark:text-slate-950',
  ghost:
    'text-slate-700 hover:bg-slate-200/70 disabled:text-slate-400 dark:text-slate-200 dark:hover:bg-slate-800',
};

export interface ButtonProps extends ComponentProps<'button'> {
  variant?: Variant;
  size?: 'sm' | 'md';
}

export function Button({ variant = 'secondary', size = 'md', className = '', type = 'button', ...props }: ButtonProps) {
  const sizing = size === 'sm' ? 'px-2.5 py-1 text-sm' : 'px-3.5 py-2 text-sm';
  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500 disabled:cursor-not-allowed ${sizing} ${VARIANTS[variant]} ${className}`}
      {...props}
    />
  );
}
