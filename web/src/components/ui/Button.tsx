import { cva, type VariantProps } from "class-variance-authority";
import { forwardRef, type ButtonHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/** .btn from style.css: main (solid primary), secondary, ghost, danger; small, normal, large. */
export const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-ctl font-semibold transition-colors no-underline hover:no-underline " +
    "disabled:cursor-not-allowed disabled:border disabled:border-line disabled:bg-subtle disabled:text-faint disabled:shadow-none [&_svg]:size-[18px]",
  {
    variants: {
      variant: {
        primary: "bg-primary text-white shadow-sm hover:bg-primary-hover active:bg-primary-active",
        secondary: "border border-line-strong bg-surface text-ink-heading shadow-sm hover:border-faint hover:bg-hover",
        ghost: "bg-transparent text-ink-heading hover:bg-subtle",
        danger: "border border-line-strong bg-surface text-danger-text shadow-sm hover:border-danger hover:bg-danger-tint",
      },
      size: {
        sm: "h-[30px] rounded-lg px-2.5 text-[13px]",
        md: "h-ctl px-4 text-[15px]",
        lg: "h-[52px] rounded-xl px-6 text-base",
      },
    },
    defaultVariants: { variant: "primary", size: "md" },
  },
);

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof buttonVariants>;

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(({ className, variant, size, type = "button", ...props }, ref) => (
  <button ref={ref} type={type} className={cn(buttonVariants({ variant, size }), className)} {...props} />
));
Button.displayName = "Button";
