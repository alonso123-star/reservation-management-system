import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './app/App'
import { BrowserRouter } from 'react-router-dom'
import './app/styles.css'
import './features/auth/auth.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode><BrowserRouter><App /></BrowserRouter></StrictMode>,
)
