import * as React from "react";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

const Dialog = DialogPrimitive.Root;
const DialogTrigger = DialogPrimitive.Trigger;
const DialogClose = DialogPrimitive.Close;

function Overlay({ className }: { className?: string }) {
  return (
    <DialogPrimitive.Overlay
      className={cn("fixed inset-0 z-40 bg-[rgba(32,33,36,0.6)] animate-fade-in", className)}
    />
  );
}

/** Centred modal, 8px radius, elevated — used only for confirmations and small forms. */
const DialogContent = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content> & { title: string; description?: string }
>(({ className, children, title, description, ...props }, ref) => (
  <DialogPrimitive.Portal>
    <Overlay />
    <DialogPrimitive.Content
      ref={ref}
      className={cn(
        "fixed left-1/2 top-1/2 z-50 w-[min(480px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 rounded-lg bg-white p-6 shadow-menu animate-fade-in focus:outline-none",
        className,
      )}
      {...props}
    >
      <DialogPrimitive.Title className="font-display text-lg font-normal text-gcp-text">{title}</DialogPrimitive.Title>
      {description && (
        <DialogPrimitive.Description className="mt-2 text-sm text-gcp-text2">{description}</DialogPrimitive.Description>
      )}
      <div className="mt-4">{children}</div>
    </DialogPrimitive.Content>
  </DialogPrimitive.Portal>
));
DialogContent.displayName = "DialogContent";

/** Right-hand side panel — the console's home for details and resolution work. */
const Sheet = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content> & {
    title: string;
    description?: string;
    width?: string;
  }
>(({ className, children, title, description, width = "560px", ...props }, ref) => (
  <DialogPrimitive.Portal>
    <Overlay className="bg-[rgba(32,33,36,0.32)]" />
    <DialogPrimitive.Content
      ref={ref}
      style={{ width: `min(${width}, 100vw)` }}
      className={cn(
        "fixed right-0 top-0 z-50 flex h-full flex-col bg-white shadow-menu animate-slide-in-right focus:outline-none",
        className,
      )}
      {...props}
    >
      <header className="flex items-start justify-between gap-4 border-b border-gcp-border px-6 py-4">
        <div>
          <DialogPrimitive.Title className="font-display text-lg font-normal text-gcp-text">{title}</DialogPrimitive.Title>
          {description && (
            <DialogPrimitive.Description className="mt-1 text-sm text-gcp-text2">{description}</DialogPrimitive.Description>
          )}
        </div>
        <DialogPrimitive.Close asChild>
          <Button variant="ghost" size="iconSm" aria-label="Close panel">
            <X className="h-4 w-4" />
          </Button>
        </DialogPrimitive.Close>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
    </DialogPrimitive.Content>
  </DialogPrimitive.Portal>
));
Sheet.displayName = "Sheet";

interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  danger?: boolean;
  loading?: boolean;
  onConfirm: () => void;
}

export function ConfirmDialog({
  open, onOpenChange, title, description, confirmLabel, danger, loading, onConfirm,
}: ConfirmDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={title} description={description}>
        <div className="flex justify-end gap-2">
          <DialogClose asChild>
            <Button variant="text" disabled={loading}>Cancel</Button>
          </DialogClose>
          <Button variant={danger ? "danger" : "primary"} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export { Dialog, DialogTrigger, DialogClose, DialogContent, Sheet };
