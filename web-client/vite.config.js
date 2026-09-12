import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// No dev-server proxy to the Tomcat server -- deliberately. Ex4's whole point is a genuinely cross-origin web
// client (this dev server on localhost:5173, Tomcat on localhost:8080), and every src/api/client.js call is a
// real fetch() straight to the server's own origin with credentials: "include". A Vite proxy would make requests
// same-origin from the browser's point of view and hide the exact problem (cookie + CORS) this exercise is about.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173
  }
});
