import * as React from "react";

import { cn } from "@/lib/utils";

const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type, ...props }, ref) => (
    <input
      type={type}
      className={cn(
        "flex h-9 w-full rounded border border-gcp-border bg-white px-3 text-sm text-gcp-text placeholder:text-gcp-text3",
        "hover:border-gcp-text2 focus:border-gcp-blue focus:outline-none focus:ring-1 focus:ring-gcp-blue",
        "disabled:cursor-not-allowed disabled:bg-gcp-ground disabled:text-gcp-text3",
        "aria-[invalid=true]:border-gcp-red aria-[invalid=true]:ring-gcp-red",
        "file:mr-3 file:rounded file:border-0 file:bg-gcp-blueBg file:px-3 file:py-1 file:text-sm file:font-medium file:text-gcp-blue",
        className,
      )}
      ref={ref}
      {...props}
    />
  ),
);
Input.displayName = "Input";

const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea
      className={cn(
        "flex min-h-[96px] w-full rounded border border-gcp-border bg-white px-3 py-2 text-sm text-gcp-text placeholder:text-gcp-text3",
        "hover:border-gcp-text2 focus:border-gcp-blue focus:outline-none focus:ring-1 focus:ring-gcp-blue",
        "disabled:cursor-not-allowed disabled:bg-gcp-ground",
        "aria-[invalid=true]:border-gcp-red aria-[invalid=true]:ring-gcp-red",
        className,
      )}
      ref={ref}
      {...props}
    />
  ),
);
Textarea.displayName = "Textarea";

const Select = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, children, ...props }, ref) => (
    <select
      className={cn(
        "flex h-9 w-full appearance-none rounded border border-gcp-border bg-white px-3 pr-8 text-sm text-gcp-text",
        "bg-[url('data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%2220%22 height=%2220%22 viewBox=%220 0 24 24%22 fill=%22%235f6368%22><path d=%22M7 10l5 5 5-5z%22/></svg>')] bg-[length:20px] bg-[right_6px_center] bg-no-repeat",
        "hover:border-gcp-text2 focus:border-gcp-blue focus:outline-none focus:ring-1 focus:ring-gcp-blue",
        "disabled:cursor-not-allowed disabled:bg-gcp-ground",
        className,
      )}
      ref={ref}
      {...props}
    >
      {children}
    </select>
  ),
);
Select.displayName = "Select";

export { Input, Textarea, Select };
