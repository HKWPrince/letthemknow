import * as React from "react";
import * as LabelPrimitive from "@radix-ui/react-label";

import { cn } from "@/lib/utils";

const Label = React.forwardRef<
  React.ElementRef<typeof LabelPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof LabelPrimitive.Root>
>(({ className, ...props }, ref) => (
  <LabelPrimitive.Root
    ref={ref}
    className={cn("text-xs font-medium text-gcp-text2 peer-disabled:opacity-60", className)}
    {...props}
  />
));
Label.displayName = "Label";

interface FieldProps {
  label: string;
  htmlFor?: string;
  hint?: React.ReactNode;
  error?: string;
  required?: boolean;
  className?: string;
  children: React.ReactNode;
}

/** Label above, control, then hint or error below — the console's stacked form rhythm. */
export function Field({ label, htmlFor, hint, error, required, className, children }: FieldProps) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      {/* asterisk via CSS so the accessible name stays exactly the label text */}
      <Label htmlFor={htmlFor} className={cn(required && "after:ml-0.5 after:text-gcp-red after:content-['*']")}>
        {label}
      </Label>
      {children}
      {error ? (
        <p className="text-xs text-gcp-red" role="alert">{error}</p>
      ) : hint ? (
        <p className="text-xs text-gcp-text3">{hint}</p>
      ) : null}
    </div>
  );
}

export { Label };
