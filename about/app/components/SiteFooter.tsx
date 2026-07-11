import { copy, localeLabels, locales, type Locale } from "../i18n";

export default function SiteFooter({ locale }: { locale: Locale }) {
  const content = copy[locale];
  return (
    <footer className="site-footer">
      <div className="site-footer-inner">
        <nav className="site-footer-links" aria-label={content.nav.about}>
          <a href={`/${locale}`}>About</a>
          <a href={`/${locale}/privacy`}>{content.legal.privacy}</a>
          <a href={`/${locale}/terms`}>{content.legal.terms}</a>
          <a href="mailto:support@hanlu.app">{content.support}</a>
        </nav>
        <nav className="footer-languages" aria-label={content.nav.language}>
          {locales.map((item) => (
            <a
              key={item}
              href={`/${item}`}
              hrefLang={copy[item].htmlLang}
              lang={copy[item].htmlLang}
              aria-current={item === locale ? "page" : undefined}
            >
              {localeLabels[item].full}
            </a>
          ))}
        </nav>
        <p>keyboard.hanlu.app</p>
      </div>
    </footer>
  );
}
