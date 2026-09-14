import type { ComponentPropsWithoutRef } from 'react';
import { buttonClassName, type ButtonSize, type ButtonVariant } from './buttonClassName';

interface ButtonProps extends ComponentPropsWithoutRef<'button'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  isLoading?: boolean;
}

export function Button({
  variant = 'primary',
  size = 'md',
  isLoading = false,
  type = 'button',
  disabled,
  className,
  children,
  ...rest
}: ButtonProps) {
  const classes = className ? `${buttonClassName(variant, size)} ${className}` : buttonClassName(variant, size);
  return (
    <button type={type} className={classes} disabled={disabled || isLoading} aria-busy={isLoading} {...rest}>
      {children}
    </button>
  );
}
