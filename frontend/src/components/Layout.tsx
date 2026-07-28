import { Link, useLocation } from 'react-router-dom'
import { useLogout } from '../hooks/useAuth'
import '../styles/layout.css'

interface LayoutProps {
  children: React.ReactNode
}

export default function Layout({ children }: LayoutProps) {
  const location = useLocation()
  const logout = useLogout()

  const handleLogout = () => {
    logout()
    window.location.href = '/login'
  }

  const isActive = (path: string) => location.pathname === path

  return (
    <div className="layout">
      <header className="header">
        <div className="header-content">
          <Link to="/" className="logo">
            🔐 LedgerGuard Console
          </Link>
          <button onClick={handleLogout} className="logout-btn">
            Logout
          </button>
        </div>
      </header>

      <aside className="sidebar">
        <nav className="nav">
          <Link
            to="/"
            className={`nav-link ${isActive('/') ? 'active' : ''}`}
          >
            📊 Dashboard
          </Link>
          <Link
            to="/transactions"
            className={`nav-link ${isActive('/transactions') ? 'active' : ''}`}
          >
            💳 Transactions
          </Link>
          <Link
            to="/dlt"
            className={`nav-link ${isActive('/dlt') ? 'active' : ''}`}
          >
            ⚠️ Dead-Letter Topic
          </Link>
          <Link
            to="/audit"
            className={`nav-link ${isActive('/audit') ? 'active' : ''}`}
          >
            📋 Audit Trail
          </Link>
        </nav>
      </aside>

      <main className="main">
        {children}
      </main>
    </div>
  )
}
