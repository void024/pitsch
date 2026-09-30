import type { ReactNode } from 'react';
import type { BadgeTone } from '../../types';
import { cx } from '../../utils/format';

interface BadgeProps {
  tone?: BadgeTone;
  children: ReactNode;
}

export function Badge({ tone = 'neutral', children }: BadgeProps) {
  return <span className={cx('badge', `badge-${tone}`)}>{children}</span>;
}