import type { Config } from "tailwindcss";
import animate from "tailwindcss-animate";

export default {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: { "2xl": "1400px" },
    },
    extend: {
      fontFamily: {
        // Google Sans is not a public web font; the stack degrades to Roboto exactly as the console does.
        display: ['"Google Sans"', '"Product Sans"', "Roboto", "system-ui", "sans-serif"],
        sans: ["Roboto", "system-ui", "-apple-system", "Segoe UI", "sans-serif"],
        mono: ['"Roboto Mono"', "ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      colors: {
        gcp: {
          blue: "#1a73e8",
          blueHover: "#1765cc",
          blueBg: "#e8f0fe",
          green: "#1e8e3e",
          greenBg: "#e6f4ea",
          yellow: "#f9ab00",
          yellowText: "#b06000",
          yellowBg: "#fef7e0",
          red: "#d93025",
          redBg: "#fce8e6",
          purple: "#8430ce",
          purpleBg: "#f3e8fd",
          text: "#202124",
          text2: "#5f6368",
          text3: "#80868b",
          border: "#dadce0",
          ground: "#f8f9fa",
          hover: "#f1f3f4",
          selected: "#e8f0fe",
        },
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      boxShadow: {
        // The console's only elevation: menus and drawers.
        menu: "0 1px 3px rgba(60,64,67,.3), 0 4px 8px 3px rgba(60,64,67,.15)",
      },
      keyframes: {
        "slide-in-right": {
          from: { transform: "translateX(100%)" },
          to: { transform: "translateX(0)" },
        },
        "fade-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
      },
      animation: {
        "slide-in-right": "slide-in-right 220ms cubic-bezier(0.4,0,0.2,1)",
        "fade-in": "fade-in 150ms ease-out",
      },
    },
  },
  plugins: [animate],
} satisfies Config;
