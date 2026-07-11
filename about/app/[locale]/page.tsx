import { notFound } from "next/navigation";
import Image from "next/image";
import { copy, isLocale, localeLabels, locales } from "../i18n";

const releaseLinks = {
  androidApk: process.env.NEXT_PUBLIC_ANDROID_APK_URL ?? "https://downloads.hanlu.app/furigana-keyboard/v1.0.0-beta.2.apk",
  appStore: process.env.NEXT_PUBLIC_APP_STORE_URL ?? "",
};

type PageProps = { params: Promise<{ locale: string }> };

export function generateStaticParams() {
  return locales.map((locale) => ({ locale }));
}

function ReleaseAction({ href, availableLabel, pending, pendingLabel }: { href: string; availableLabel: string; pending: string; pendingLabel: string }) {
  if (!href) {
    return <span className="release-action is-pending" aria-label={pendingLabel}>{pending}</span>;
  }
  return <a className="release-action" href={href} rel="noreferrer">{availableLabel}<span aria-hidden="true">↗</span></a>;
}

const APPLE_PATH = "M788.1 340.9c-5.8 4.5-108.2 62.2-108.2 190.5 0 148.4 130.3 200.9 134.2 202.2-.6 3.2-20.7 71.9-68.7 141.9-42.8 61.6-87.5 123.1-155.5 123.1s-85.5-39.5-164-39.5c-76.5 0-103.7 40.8-165.9 40.8s-105.6-57-155.5-127C46.7 790.7 0 663 0 541.8c0-194.4 126.4-297.5 250.8-297.5 66.1 0 121.2 43.4 162.7 43.4 39.5 0 101.1-46 176.3-46 28.5 0 130.9 2.6 198.3 99.2zm-234-181.5c31.1-36.9 53.1-88.1 53.1-139.3 0-7.1-.6-14.3-1.9-20.1-50.6 1.9-110.8 33.7-147.1 75.8-28.5 32.4-55.1 83.6-55.1 135.5 0 7.8 1.3 15.6 1.9 18.1 3.2.6 8.4 1.3 13.6 1.3 45.4 0 102.5-30.4 135.5-71.3z";

function AppleIcon() {
  return <svg className="platform-glyph apple-glyph" viewBox="0 0 814 1000"><path d={APPLE_PATH} fill="currentColor" /></svg>;
}

function GooglePlayIcon() {
  return (
    <svg className="platform-glyph google-play-glyph" viewBox="0 0 28.99 31.99">
      <path d="M13.54 15.28.12 29.34a3.66 3.66 0 0 0 5.33 2.16l15.1-8.6Z" fill="#EA4335" />
      <path d="m27.11 12.89-6.53-3.74-7.35 6.45 7.38 7.28 6.48-3.7a3.54 3.54 0 0 0 1.5-4.79 3.62 3.62 0 0 0-1.5-1.5z" fill="#FBBC04" />
      <path d="M.12 2.66a3.57 3.57 0 0 0-.12.92v24.84a3.57 3.57 0 0 0 .12.92L14 15.64Z" fill="#4285F4" />
      <path d="m13.64 16 6.94-6.85L5.5.51A3.73 3.73 0 0 0 3.63 0 3.64 3.64 0 0 0 .12 2.65Z" fill="#34A853" />
    </svg>
  );
}

export default async function LocalePage({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const content = copy[locale];

  return (
    <main>
      <nav className="nav shell" aria-label={content.nav.label}>
        <a className="brand" href={`/${locale}#top`} aria-label={content.nav.top}>
          <span className="brand-mark" aria-hidden="true">
            <Image className="brand-icon" src="/app-icon.png" alt="" width={512} height={512} />
          </span>
          <span>Furigana Keyboard</span>
        </a>
        <div className="nav-actions">
          <div className="nav-links">
            <a href="#features">{content.nav.features}</a>
            <a href="#download">{content.nav.download}</a>
            <a href="#about">{content.nav.about}</a>
          </div>
          <div className="language-switcher" aria-label={content.nav.language}>
            {locales.map((item) => (
              <a key={item} href={`/${item}`} hrefLang={copy[item].htmlLang} lang={copy[item].htmlLang} aria-current={item === locale ? "page" : undefined} aria-label={localeLabels[item].full} title={localeLabels[item].full}>
                {localeLabels[item].short}
              </a>
            ))}
          </div>
        </div>
      </nav>

      <section className="hero shell" id="top">
        <div className="hero-copy">
          <h1>{content.hero.title[0]}<br /><em>{content.hero.title[1]}</em></h1>
          <p className="hero-lead">{content.hero.lead[0]}<br />{content.hero.lead[1]}</p>
          <a className="text-link" href="#download">{content.hero.release} <span aria-hidden="true">↓</span></a>
        </div>

        <figure className="keyboard-stage">
          <Image
            className="keyboard-preview"
            src="/keyboard-preview.jpg"
            alt={content.hero.keyboardImage}
            width={1080}
            height={922}
            priority
            sizes="(max-width: 820px) calc(100vw - 32px), 520px"
          />
        </figure>
      </section>

      <section className="feature-section" id="features">
        <div className="shell">
          <div className="section-heading"><p>WHY FURIGANA KEYBOARD</p><h2>{content.why.heading}</h2></div>
          <div className="feature-grid">
            {content.why.features.map((feature, index) => (
              <article key={feature.title}><span>0{index + 1}</span><h3>{feature.title}</h3><p>{feature.body}</p></article>
            ))}
          </div>
        </div>
      </section>

      <section className="download-section shell" id="download">
        <div className="section-heading compact"><p>DOWNLOAD</p><h2>{content.download.heading}</h2><span>{content.download.intro}</span></div>
        <div className="release-grid">
          <article className="release-card android">
            <div className="platform-icon" aria-hidden="true"><GooglePlayIcon /></div>
            <div><p className="platform">ANDROID</p><h3>{content.download.androidTitle}</h3><p>{content.download.androidBody}</p></div>
            <ReleaseAction href={releaseLinks.androidApk} availableLabel={content.download.apk} pending={content.download.pending} pendingLabel={content.download.pendingLabel} />
          </article>
          <article className="release-card ios">
            <div className="platform-icon" aria-hidden="true"><AppleIcon /></div>
            <div><p className="platform">IPHONE &amp; IPAD</p><h3>{content.download.iosTitle}</h3><p>{content.download.iosBody}</p></div>
            <ReleaseAction href={releaseLinks.appStore} availableLabel={content.download.appStore} pending={content.download.pending} pendingLabel={content.download.pendingLabel} />
          </article>
        </div>
      </section>

      <section className="about-section" id="about">
        <div className="shell about-grid">
          <div className="about-title"><p>ABOUT</p><h2>{content.about.heading[0]}<br />{content.about.heading[1]}</h2></div>
          <div className="about-copy">
            <p className="large-copy">{content.about.lead}</p><p>{content.about.body}</p>
            <div className="facts">
              <div><strong>6,356</strong><span>{content.about.facts[0]}</span></div>
              <div><strong>13,108</strong><span>{content.about.facts[1]}</span></div>
              <div><strong>0</strong><span>{content.about.facts[2]}</span></div>
            </div>
          </div>
        </div>
      </section>

      <footer className="shell">
        <div className="brand footer-brand">
          <span className="brand-mark" aria-hidden="true">
            <Image className="brand-icon" src="/app-icon.png" alt="" width={512} height={512} />
          </span>
          <span>Furigana Keyboard</span>
        </div>
        <p>{content.footer}</p>
        <nav className="footer-links" aria-label={content.nav.about}>
          <a href={`/${locale}/terms`}>{content.legal.terms}</a>
          <a href={`/${locale}/privacy`}>{content.legal.privacy}</a>
          <a href="mailto:support@hanlu.app">{content.support}: support@hanlu.app</a>
        </nav>
        <p>© 2026 Furigana Keyboard</p>
      </footer>
    </main>
  );
}
