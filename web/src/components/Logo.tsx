/**
 * The product mark: two overlapping speech bubbles — one message, then the reply it sets off.
 * Single source of truth; `public/favicon.svg` carries the identical paths.
 */
export function LogoMark({ size = 26, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 26 26" className={className} role="img" aria-label="LetThemKnow">
      <path
        d="M4 5.5A2.5 2.5 0 0 1 6.5 3h9A2.5 2.5 0 0 1 18 5.5v6a2.5 2.5 0 0 1-2.5 2.5H10l-4 3.5V14A2.5 2.5 0 0 1 4 11.5z"
        fill="#1a73e8"
      />
      <path
        d="M10 11.5A2.5 2.5 0 0 1 12.5 9h8a2.5 2.5 0 0 1 2.5 2.5v5.5a2.5 2.5 0 0 1-2.5 2.5H16l-3.5 3v-3h-.5A2.5 2.5 0 0 1 10 17z"
        fill="#1e8e3e"
        opacity=".92"
      />
    </svg>
  );
}

export function Wordmark() {
  return (
    <div className="flex items-center gap-2.5">
      <LogoMark />
      <span className="font-display text-[18px] text-gcp-text">
        Let<span className="font-medium">Them</span>Know
      </span>
    </div>
  );
}
