import { createContext, useContext, useState, type ReactNode } from 'react'
import { en, type Translations } from './locales/en'
import { zh } from './locales/zh'

type Language = 'en' | 'zh'
const STORAGE_KEY = 'gateway-language'

const translations: Record<Language, Translations> = { en, zh }

interface I18nContextType {
  lang: Language
  t: Translations
  setLang: (lang: Language) => void
}

const I18nContext = createContext<I18nContextType | null>(null)

function getInitialLanguage(): Language {
  if (typeof window === 'undefined') return 'en'
  const stored = localStorage.getItem(STORAGE_KEY) as Language | null
  if (stored === 'en' || stored === 'zh') return stored
  // Default to browser language
  const browserLang = navigator.language?.startsWith('zh') ? 'zh' : 'en'
  return browserLang
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Language>(getInitialLanguage)

  const setLang = (newLang: Language) => {
    setLangState(newLang)
    localStorage.setItem(STORAGE_KEY, newLang)
  }

  const value: I18nContextType = {
    lang,
    t: translations[lang],
    setLang,
  }

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useTranslation(): I18nContextType {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useTranslation must be used within I18nProvider')
  return ctx
}
