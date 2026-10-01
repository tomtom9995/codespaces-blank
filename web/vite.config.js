import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Für `npm run dev` ohne Docker: /api an die lokal laufende API weiterleiten.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:3000" } },
});
