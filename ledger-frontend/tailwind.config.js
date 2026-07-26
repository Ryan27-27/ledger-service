/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: {
          DEFAULT: "#0A0E17",
          panel: "#111827",
          raised: "#161F32",
          border: "#232E45",
        },
        text: {
          primary: "#E7EBF3",
          muted: "#8792A3",
          faint: "#4B5468",
        },
        credit: {
          DEFAULT: "#2FD9A8",
          dim: "#1B7A5F",
          bg: "rgba(47, 217, 168, 0.08)",
        },
        debit: {
          DEFAULT: "#F0665A",
          dim: "#8C3931",
          bg: "rgba(240, 102, 90, 0.08)",
        },
        amber: {
          DEFAULT: "#E8A93C",
          bg: "rgba(232, 169, 60, 0.1)",
        },
      },
      fontFamily: {
        display: ["'Space Grotesk'", "sans-serif"],
        body: ["'Inter'", "sans-serif"],
        mono: ["'JetBrains Mono'", "monospace"],
      },
    },
  },
  plugins: [],
}
