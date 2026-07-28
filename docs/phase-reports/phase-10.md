# Phase 10: Frontend Operations Console (Foundation)

**Status**: ✅ FOUNDATION COMPLETE

**Acceptance Gate**: ✅ React TypeScript scaffold with core pages and API integration layer
- Project structure: Vite + React 18 + TypeScript
- Pages: Dashboard, Transaction Search, DLT Explorer, Audit Trail
- Styling: CSS modules with responsive design
- API Integration: Axios client with interceptors for authentication
- Authentication: JWT token storage and login flow
- All modules ready for backend integration

## What Was Built

### 1. Project Scaffolding (React + Vite + TypeScript)

**Technology Stack**:
- **Build Tool**: Vite 5.4.3 (ES modules, HMR, tree-shaking)
- **Framework**: React 18.3.1 + React Router 6.28.0
- **Language**: TypeScript 5.5.2 (strict mode enabled)
- **HTTP Client**: Axios 1.7.7 with request interceptors
- **Styling**: CSS modules per component + global theme variables

**Package Configuration**:
- `npm run dev`: Vite dev server on localhost:3000
- `npm run build`: Production bundle (TypeScript compilation + minification)
- `npm run preview`: Preview production build locally
- `npm run type-check`: TypeScript noEmit check
- Vite proxy configured: `/api` routes to `http://localhost:8080`

**TypeScript Configuration**:
- Target: ES2020 (modern browser compatibility)
- Strict: true (no implicit `any`, null checks)
- JSX: react-jsx (React 17+ style, no React import needed)
- Path aliases: `@/*` → `src/*` for cleaner imports

### 2. Core Components

**App.tsx** (Root Component):
- BrowserRouter integration with route definitions
- Auth check: redirects unauthenticated users to login
- Layout wrapper for authenticated views
- Routes: `/` (dashboard), `/transactions`, `/dlt`, `/audit`

**Layout Component** (`components/Layout.tsx`):
- Header with branding and logout button
- Sidebar navigation with active link highlighting
- Main content area with scroll isolation
- Responsive: sidebar collapses on mobile (<768px)

**CSS Design System**:
- CSS variables for colors, spacing, transitions
- Consistent button/input styling
- Dark/light theme ready (variables can be toggled)
- Mobile-responsive grid layouts

### 3. Pages (4 Core Views)

**Dashboard** (`pages/Dashboard.tsx`):
- 6-card metrics grid: Projection Lag, DLT Depth, Consumer Lag, Tx Rate, Match Rate, Error Rate
- Live metrics refresh (5-second interval)
- Alert section: Health status, DLT warnings, lag alerts
- Responsive grid: auto-fits columns for mobile

**Transaction Search** (`pages/TransactionSearch.tsx`):
- Search form with query input
- Results table: Transaction ID, Status, Amount, Timestamp, Trace ID
- Details panel: Shows selected transaction details
- Status badges (PENDING/MATCHED/FAILED)
- Link to Zipkin for trace visualization

**DLT Explorer** (`pages/DltExplorer.tsx`):
- Message list table: Topic, Partition, Offset, Reason, Timestamp
- Replay button per message
- Details panel: Shows full message context
- Envelope preview (truncated JSON)
- Stack trace digest display

**Audit Trail** (`pages/AuditTrail.tsx`):
- Search by correlation ID
- Timeline view with markers (left-side timeline)
- Action badges (color-coded operations)
- Entry metadata: Actor, Event Type, Aggregate ID
- Full audit details per entry

**Login Page** (`pages/LoginPage.tsx`):
- Centered login form (gradient background)
- Username/password inputs with validation
- Loading state during login
- Demo credentials display (admin, operator, analyst)
- Token storage in localStorage

### 4. API Integration Layer

**HTTP Client** (`api/client.ts`):
- Axios instance with `/api/v1` base URL
- Request interceptor: auto-includes `Authorization: Bearer {token}` header
- Token read from `localStorage.auth_token`
- 10-second timeout on all requests
- Vite proxy: dev requests to `/api` forward to backend

**API Queries** (`api/queries.ts`):
- **transactionApi**:
  - `search(query, limit)`: Search transactions
  - `getById(transactionId)`: Fetch single transaction
  - `getLifecycle(transactionId)`: Get transaction lifecycle
- **dltApi**:
  - `list(limit, offset)`: Paginated DLT messages
  - `replay(originalEnvelope)`: Trigger DLT message replay
- **auditApi**:
  - `search(correlationId?, actor?, limit)`: Search audit entries
  - `getByCorrelationId(correlationId)`: Get entries for correlation
- **metricsApi**:
  - `dashboard()`: Get 6 dashboard metrics
  - `projectionLag()`: Single metric endpoint
  - `dltDepth()`: Single metric endpoint

**Type Definitions**:
- `Transaction`: transactionId, status, amount, currency, timestamp, correlationId, traceId
- `DltMessage`: messageId, topic, partition, offset, reason, stackTraceDigest, timestamp, originalEnvelope
- `AuditEntry`: id, timestamp, actor, action, aggregateId, eventType, details, correlationId
- `DashboardMetrics`: projectionLag, dltDepth, consumerLag, transactionRate, matchRate, errorRate

### 5. Authentication & Hooks

**useAuth Hook** (`hooks/useAuth.ts`):
- Reads `auth_token` and `user` from localStorage
- Returns `isAuthenticated`, `isLoading`, `user` object
- User object: `{ name, roles: string[] }`

**useLogin Hook**:
- POST to `/api/v1/auth/login` with username/password
- Stores token and user in localStorage on success
- Returns boolean success indicator

**useLogout Hook**:
- Clears auth_token and user from localStorage
- Called by Layout on logout button click

### 6. Styling & Layout

**Global CSS** (`index.css`):
- CSS variables: colors, fonts, transitions
- Base styles: body, inputs, buttons, links
- Utility classes: loading, error, empty-state
- Table styling: striped rows, hover effects

**Dashboard CSS**:
- Metrics grid with 3 columns (auto-fit)
- Metric cards with hover lift effect
- Color-coded status indicators
- Alert boxes (success/warning/error)

**Login CSS**:
- Gradient background (purple/blue)
- Centered white form container
- Focus states on inputs
- Demo credentials section

**Layout CSS**:
- CSS Grid: 2-column layout (sidebar + main)
- Header with flex layout
- Sidebar navigation with active link indicator
- Responsive: sidebar hides on mobile

**Page CSS Files**:
- `dlt.css`: Two-column message list + details panel
- `search.css`: Search results table + details sidebar
- `audit.css`: Timeline view with color-coded markers

## Architecture Decision

### Single-Page Application (SPA)

**Why React over traditional server-rendered templates**:
- Real-time updates: WebSocket subscription layer can be added for live metrics
- Responsive interactions: no full-page reloads for searches/filters
- Component reusability: metric cards, tables, forms used across pages
- Developer experience: TypeScript static typing catches errors at build time

### API-Driven Design

**Separation of concerns**:
- Backend: REST APIs only (query-service, transaction-service endpoints)
- Frontend: React components + state management
- No server-side rendering or template logic
- Backend agnostic to UI technology

### JWT Authentication

**Token-based auth**:
- Stateless: no session storage on server
- Works across HTTP and WebSocket (can add later)
- Suitable for SPA where frontend controls token lifetime
- localStorage storage simplifies cookie-free deployments

### CSS Variables for Theming

**Design system scalability**:
- Single source of truth for colors/spacing
- Easy to implement dark mode (change variable values)
- No CSS-in-JS bloat
- Familiar to backend devs (similar to Spring properties)

## How It Holds Up

### Scalability: Component-Based Architecture

- Each page is independent (can be code-split)
- API queries isolated in `api/` folder
- Hooks encapsulate logic (auth, data fetching)
- Easy to add new pages/components

### Maintainability: TypeScript Strict Mode

- All implicit `any` errors caught at compile time
- Props/state types enforced
- IDE autocomplete for API responses
- Refactoring safer (type changes propagate)

### Accessibility: Semantic HTML

- Form inputs with labels
- Table headers (`<thead>/<tbody>`)
- Button `disabled` attribute
- Links for navigation (not styled buttons)

### Performance: Vite + Tree-Shaking

- ES modules for optimal bundling
- Unused code eliminated by default
- Fast refresh (HMR on dev)
- Production build minified and gzipped

## Test Results

### Build & Development

```
✓ TypeScript compilation (strict mode, no errors)
✓ Vite dev server configured (port 3000, /api proxy)
✓ All imports resolve (component, hooks, API, styles)
✓ Hot module replacement ready
```

### Structure Validation

```
frontend/
  ├── src/
  │   ├── components/     ✓ Layout.tsx
  │   ├── pages/          ✓ Dashboard, TransactionSearch, DltExplorer, AuditTrail, LoginPage
  │   ├── hooks/          ✓ useAuth, useLogin, useLogout
  │   ├── api/            ✓ client.ts, queries.ts (types + endpoints)
  │   ├── styles/         ✓ 6 CSS files (dashboard, login, layout, dlt, search, audit)
  │   ├── App.tsx         ✓ React Router setup
  │   └── main.tsx        ✓ React DOM entry
  ├── index.html          ✓ DOM root
  ├── vite.config.ts      ✓ Dev server + proxy
  ├── tsconfig.json       ✓ Strict TypeScript config
  └── package.json        ✓ React 18, TypeScript, Vite, Axios
```

## Key Decisions Locked In

1. **Vite for Build**: Fast HMR, ES modules, minimal config
2. **React + TypeScript**: Type safety, component reusability, large ecosystem
3. **React Router v6**: Latest patterns, loader integration ready
4. **Axios for HTTP**: Interceptor support for auth headers
5. **CSS Modules**: No naming conflicts, design system via CSS variables
6. **JWT in localStorage**: Simplicity for SPA auth
7. **Separate `/api` folder**: Queries co-located with TypeScript types

## Files

**New** (Phase 10 Foundation):
- `frontend/package.json`: Dependencies and build scripts
- `frontend/tsconfig.json`: TypeScript configuration (strict mode)
- `frontend/vite.config.ts`: Vite dev server + API proxy
- `frontend/index.html`: DOM root
- `frontend/src/main.tsx`: React DOM entry
- `frontend/src/App.tsx`: Router configuration
- `frontend/src/components/Layout.tsx`: Header + sidebar + main
- `frontend/src/pages/{Dashboard,TransactionSearch,DltExplorer,AuditTrail,LoginPage}.tsx`
- `frontend/src/hooks/useAuth.ts`: Authentication hooks
- `frontend/src/api/{client.ts,queries.ts}`: HTTP client + endpoint definitions
- `frontend/src/styles/{layout,dashboard,login,dlt,search,audit}.css`
- `frontend/src/index.css`: Global styles + design system
- `frontend/.gitignore`: Node + IDE ignores
- `docs/phase-reports/phase-10.md`: This documentation

## Next Steps (Phase 10 Continued / Phase 11)

1. **Backend REST Endpoints** (Phase 10 continued)
   - Implement transaction search endpoint (`GET /api/v1/transactions/search`)
   - Implement DLT list endpoint (`GET /api/v1/replay/dlt-messages`)
   - Implement audit search endpoint (`GET /api/v1/audit/entries`)
   - Implement metrics dashboard endpoint (`GET /api/v1/metrics/dashboard`)

2. **Advanced Features** (Phase 10+)
   - Real-time metrics via WebSocket (replace 5-second polling)
   - Export audit trail to CSV
   - Trace viewer integrated (Zipkin link works, embed later)
   - Role-based UI (hide DLT replay for non-operators)

3. **Testing** (Phase 11)
   - Jest + React Testing Library for component tests
   - E2E tests with Playwright
   - API mock server (msw or Mirage)

4. **Performance** (Phase 12+)
   - Code splitting per route (lazy load pages)
   - Image optimization
   - CSS purging in production build
   - Bundle analysis (vite-plugin-visualizer)

5. **Deployment** (Phase 13)
   - Docker container for frontend (nginx serving SPA)
   - Nginx config for single-page app routing
   - Environment variable injection
   - CI/CD pipeline for npm build

## ADR References

- [ADR-0013: Hexagonal Layering](adr/0013-hexagonal-layering.md) (frontend adapter layer pattern)
