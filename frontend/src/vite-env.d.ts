/// <reference types="vite/client" />

/**
 * Typed build-time configuration.
 *
 * Vite replaces `import.meta.env.*` at build time, so these are baked into the bundle and are NOT
 * secrets — anything here is readable by anyone who loads the page. The API base URL is fine;
 * credentials never are.
 */
interface ImportMetaEnv {
  /**
   * Absolute backend origin, e.g. https://ledgerguard-gateway.onrender.com
   *
   * Leave unset for the same-origin setup (Vite proxy locally, Vercel rewrite in production),
   * which is the default and avoids CORS entirely.
   */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
